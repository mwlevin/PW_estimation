package umn.pw_estimation;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import umn.pw_estimation.Input.Detector;
import umn.pw_estimation.Input.ReadData;
import umn.pw_estimation.PW.Cell;
import umn.pw_estimation.PW.CellRecord;
import umn.pw_estimation.PW.Corridor;

/**
 * Leave-one-out validation (Section 4 of the report).
 *
 * One mainline detector station is removed from the filter, and the estimate in the cell containing it is compared with
 * its 30-second measurements. Repeating this for every station of both directions tests the estimate at every station.
 * The estimate is compared with two simple alternatives: linear interpolation (by distance along the corridor) between
 * the nearest stations on each side, and the free flow speed everywhere. For the first and last station of a corridor,
 * there is a station on only one side, and "interpolation" uses that station's measurements.
 *
 * Usage: Validation <detector data CSV> <duration (s)> <EB|WB> <station number, or "list", or "none"> [dt (s)] [output CSV]
 * Stations are numbered from 0 in driving order; "list" prints them, and "none" withholds nothing (the errors are then at
 * stations the filter uses). The output CSV has one row per 30-second measurement at the withheld station.
 * Model parameters can be changed with system properties, e.g. -Dtau=60 -Dc0=10 -Dsigma_k=10 -Dsigma_v=3 -Dffs=69
 * -Dmax_sigma_k=50 -Dmax_sigma_v=25 (units: s, m/s, veh/mi/lane, mi/hr, mi/hr, veh/mi/lane, mi/hr), and the flow-density
 * relationship with -Dcapacity=2200 -Ddischarge=2200 -Dw=11.9 -Djam=172 (veh/hr/lane, veh/hr/lane, mi/hr, veh/mi/lane);
 * -Dtriangle=true sets w so the relationship is triangular. -Dramps=none places ramp detectors without the ramps file,
 * -Dunmetered=false ignores unmetered ramps, and
 * -Dsigma_r=18 sets their random walk standard deviation per time step (veh/hr). -Dprior=<file|none> sets the historical
 * averages of unmetered ramps (default data/MN610/unmetered_prior.csv), and -Dreversion=600 the time (s) to relax toward
 * them. -Dinventory=<file> uses another detector inventory.
 * The script validate.sh runs every station in parallel and summarizes the results.
 *
 * @author mlevin
 */
public class Validation {

    // the first 5 minutes are excluded while the filter adjusts from its initial estimate
    static final int WARMUP = 300;

    public static void main(String[] args) throws IOException {
        File data = new File(args[0]);
        int duration = Integer.parseInt(args[1]);
        String direction = args[2];
        String station = args[3];
        double dt = args.length > 4 ? Double.parseDouble(args[4]) : 3;
        PrintStream out = args.length > 5 ? new PrintStream(new FileOutputStream(args[5])) : System.out;

        Corridor.tau = Double.parseDouble(System.getProperty("tau", ""+Corridor.tau));
        Corridor.C_0 = Double.parseDouble(System.getProperty("c0", ""+Corridor.C_0));
        Corridor.sigma_k = Double.parseDouble(System.getProperty("sigma_k", ""+Corridor.sigma_k * 1609.3)) / 1609.3;
        Corridor.sigma_v = Double.parseDouble(System.getProperty("sigma_v", ""+Corridor.sigma_v * 2.237)) / 2.237;
        Corridor.max_sigma_k = Double.parseDouble(System.getProperty("max_sigma_k", ""+Corridor.max_sigma_k * 1609.3)) / 1609.3;
        Corridor.max_sigma_v = Double.parseDouble(System.getProperty("max_sigma_v", ""+Corridor.max_sigma_v * 2.237)) / 2.237;
        ReadData.FREE_FLOW_SPEED = Double.parseDouble(System.getProperty("ffs", ""+ReadData.FREE_FLOW_SPEED));
        Corridor.UNMETERED_RAMPS = Boolean.parseBoolean(System.getProperty("unmetered", ""+Corridor.UNMETERED_RAMPS));
        // veh/hr per time step
        Corridor.sigma_unmetered = Double.parseDouble(System.getProperty("sigma_r", ""+Corridor.sigma_unmetered * 3600)) / 3600;
        Corridor.max_unmetered = Double.parseDouble(System.getProperty("max_r", ""+Corridor.max_unmetered * 3600)) / 3600;
        Corridor.max_sigma_unmetered = Double.parseDouble(System.getProperty("max_sigma_r", ""+Corridor.max_sigma_unmetered * 3600)) / 3600;
        Corridor.MERGE_SUPPLY = Boolean.parseBoolean(System.getProperty("merge_supply", ""+Corridor.MERGE_SUPPLY));
        Corridor.unmetered_reversion = Double.parseDouble(System.getProperty("reversion", ""+Corridor.unmetered_reversion));
        ReadData.JAM_DENSITY = Double.parseDouble(System.getProperty("jam", ""+ReadData.JAM_DENSITY));
        ReadData.WAVE_SPEED = Double.parseDouble(System.getProperty("w", ""+ReadData.WAVE_SPEED));
        ReadData.CAPACITY = Double.parseDouble(System.getProperty("capacity", ""+ReadData.CAPACITY));
        // the queue discharge rate is the capacity unless given
        ReadData.DISCHARGE_RATE = Double.parseDouble(System.getProperty("discharge", ""+ReadData.CAPACITY));
        // -Dtriangle=true sets the wave speed so the congested branch meets the free flow branch at capacity
        if(Boolean.parseBoolean(System.getProperty("triangle", "false"))){
            ReadData.WAVE_SPEED = ReadData.CAPACITY / (ReadData.JAM_DENSITY - ReadData.CAPACITY / ReadData.FREE_FLOW_SPEED);
        }
        System.err.printf("flow-density: free flow speed %.1f mi/hr, capacity %.0f, discharge %.0f veh/hr/ln, w %.2f mi/hr, K %.0f veh/mi/ln%n",
                ReadData.FREE_FLOW_SPEED, ReadData.CAPACITY, ReadData.DISCHARGE_RATE, ReadData.WAVE_SPEED, ReadData.JAM_DENSITY);

        ReadData read = new ReadData();
        Map<String, Corridor> corridors = read.readOSM("610", new File("data/MN610/geometry.txt"), dt, new String[]{"WB", "EB"},
                new File(System.getProperty("inventory", "data/MN610/MN610_Edited.csv")), new String[]{"T.H.610 WB", "T.H.610 EB"},
                System.getProperty("ramps", "data/MN610/ramps.csv").equals("none")? null : new File(System.getProperty("ramps", "data/MN610/ramps.csv")));
        read.readDetectorData(data);
        String prior = System.getProperty("prior", "data/MN610/unmetered_prior.csv");
        if(!prior.equals("none")){
            read.readUnmeteredPrior(new File(prior));
        }

        Corridor corridor = corridors.get(direction);
        Cell[] cells = corridor.getCells();

        // mainline stations in driving order
        List<Cell> stations = new ArrayList<>();
        for(Cell c : cells){
            if(c.hasDetector() && c.getDetector().getType() == Detector.Type.Mainline){
                stations.add(c);
            }
        }

        if(station.equals("list")){
            for(int s = 0; s < stations.size(); s++){
                Cell c = stations.get(s);
                System.out.println(s+" cell "+c.cell_idx+" "+c.getDetector().getName()+" ("+c.getDetector().getNumLanes()+" loops, "+c.getNumLanes()+" lanes)");
            }
            return;
        }

        List<Cell> targets = new ArrayList<>();
        Detector withheld = null;
        if(station.equals("none")){
            targets.addAll(stations);
        }
        else{
            Cell c = stations.get(Integer.parseInt(station));
            targets.add(c);
            withheld = c.getDetector();
            c.setDetector(null);
        }

        long start = System.currentTimeMillis();
        corridor.init(0);
        corridor.estimate(duration, null);
        System.err.println(corridor.getName()+" station "+station+": estimated "+duration+" s in "+(System.currentTimeMillis() - start)/1000.0+" s");

        out.println("direction,station,detector,cell,time,measured u,measured k,EKF u,EKF k,interpolated u,interpolated k");
        int steps_per_interval = (int)Math.round(30 / dt);

        for(Cell c : targets){
            Detector det = withheld != null ? withheld : c.getDetector();

            // nearest stations on each side that the filter uses
            Cell up = null, down = null;
            for(Cell s : stations){
                if(s.cell_idx < c.cell_idx){
                    up = s;
                }
                else if(s.cell_idx > c.cell_idx && down == null){
                    down = s;
                }
            }

            for(int t = WARMUP; t + 30 <= duration; t += 30){
                double u_obs = det.getLast30sSpeed(t);
                double k_obs = det.getLast30sDensity(t);
                if(u_obs < 0 || k_obs < 0){
                    continue;
                }
                u_obs *= 2.23694;
                k_obs = k_obs / det.getNumLanes() * 1609.34;

                // average of the estimate over the 30 seconds of the measurement
                double u_est = 0, k_est = 0;
                int first = (int)Math.ceil(t / dt);
                for(int j = first; j < first + steps_per_interval; j++){
                    CellRecord r = corridor.records[c.cell_idx][j];
                    u_est += r.speed / steps_per_interval;
                    k_est += r.density / c.getNumLanes() / steps_per_interval;
                }

                double[] interp = interpolate(up, down, c, t);

                out.println(direction+","+stations.indexOf(c)+","+det.getName()+","+c.cell_idx+","+t+","+u_obs+","+k_obs+","+u_est+","+k_est+","
                        +(interp == null? "," : interp[0]+","+interp[1]));
            }
        }

        if(out != System.out){
            out.close();
        }
    }

    // speed (mi/hr) and density (veh/mi/lane) linearly interpolated between the stations up and down, by cell position
    // if only one of them exists or has valid data, its values are used; null if neither does
    static double[] interpolate(Cell up, Cell down, Cell c, long t){
        double[] a = measure(up, t);
        double[] b = measure(down, t);

        if(a == null && b == null){
            return null;
        }
        if(a == null){
            return b;
        }
        if(b == null){
            return a;
        }
        double w = (double)(c.cell_idx - up.cell_idx) / (down.cell_idx - up.cell_idx);
        return new double[]{a[0] + w * (b[0] - a[0]), a[1] + w * (b[1] - a[1])};
    }

    // measured speed (mi/hr) and density (veh/mi/lane) at a station, or null if missing
    static double[] measure(Cell c, long t){
        if(c == null || !c.hasDetector()){
            return null;
        }
        Detector det = c.getDetector();
        double u = det.getLast30sSpeed(t);
        double k = det.getLast30sDensity(t);
        if(u < 0 || k < 0){
            return null;
        }
        return new double[]{u * 2.23694, k / det.getNumLanes() * 1609.34};
    }
}
