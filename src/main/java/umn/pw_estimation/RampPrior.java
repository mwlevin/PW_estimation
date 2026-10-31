package umn.pw_estimation;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import umn.pw_estimation.Input.Detector;
import umn.pw_estimation.Input.ReadData;
import umn.pw_estimation.PW.Cell;
import umn.pw_estimation.PW.Coordinate;
import umn.pw_estimation.PW.Corridor;

/**
 * Historical average flows of the ramps on MN-610, by time of day.
 *
 * Unmetered ramps: from the flow balance between consecutive mainline stations: downstream station flow - upstream
 * station flow - metered entrance flows + metered exit flows. Stations and ramps are ordered by their position along the
 * corridor (the station's first loop, and the ramp's merge or diverge point), not by cell, because a ramp in the same
 * cell as a station can be on either side of it. The net flow of each segment is given to the unmetered ramps in it whose
 * type (entrance or exit) matches its sign, split equally. Ramps upstream of the first station or downstream of the last
 * have no historical average.
 *
 * Metered ramps: the sum of the counts of their loops. The filter uses these only when the ramp's detector has no data;
 * otherwise its flow estimate drifts without limit (see Corridor.predictRamp()).
 *
 * Read by ReadData.readRampPrior().
 *
 * Usage: RampPrior <output CSV> <detector data CSV> [detector data CSV ...]
 * Each data file is one day (e.g. data/MN610/daily). Do not include the days used for validation.
 *
 * @author mlevin
 */
public class RampPrior {

    static final File RAMPS = new File("data/MN610/ramps.csv");
    static final int BINS = 86400 / Cell.PRIOR_BIN;

    // a 30-second interval counts only if all detectors involved have data; a day's bin counts if at least half of
    // its intervals do
    static final int MIN_INTERVALS = Cell.PRIOR_BIN / 30 / 2;

    // a ramp from the ramps file
    static class Ramp {
        String[] columns;
        double position; // m from the start of the corridor
        int sign; // 1 entrance, -1 exit
        int segment; // index of the downstream station of its segment, or -1

        boolean isMetered(){
            return !columns[7].trim().isEmpty();
        }

        String[] loops(){
            return columns[7].trim().split(" ");
        }
    }

    public static void main(String[] args) throws IOException {
        // per direction, per segment (index of its downstream station), per bin: sum over days of the day's average, and number of days
        Map<String, double[][]> sum = new HashMap<>();
        Map<String, int[][]> days = new HashMap<>();
        // the same for each metered ramp (by its index in the ramps of its direction)
        Map<String, double[][]> metered_sum = new HashMap<>();
        Map<String, int[][]> metered_days = new HashMap<>();
        Map<String, List<Ramp>> ramps = null;

        for(int f = 1; f < args.length; f++){
            ReadData read = new ReadData();
            Map<String, Corridor> corridors = read.readOSM("610", new File("data/MN610/geometry.txt"), 3, new String[]{"WB", "EB"},
                    new File("data/MN610/MN610_Edited.csv"), new String[]{"T.H.610 WB", "T.H.610 EB"}, RAMPS);
            read.readDetectorData(new File(args[f]));
            long start = read.getStartOfDay();
            ramps = readRamps(corridors);

            for(String direction : corridors.keySet()){
                Corridor corridor = corridors.get(direction);
                List<Detector> stations = new ArrayList<>();
                for(Cell c : corridor.getCells()){
                    if(c.hasDetector() && c.getDetector().getType() == Detector.Type.Mainline){
                        stations.add(c.getDetector());
                    }
                }
                List<Ramp> direction_ramps = ramps.get(direction);
                sum.putIfAbsent(direction, new double[stations.size()][BINS]);
                days.putIfAbsent(direction, new int[stations.size()][BINS]);
                metered_sum.putIfAbsent(direction, new double[direction_ramps.size()][BINS]);
                metered_days.putIfAbsent(direction, new int[direction_ramps.size()][BINS]);

                for(int s = 1; s < stations.size(); s++){
                    double[] day_sum = new double[BINS];
                    int[] day_n = new int[BINS];

                    for(long t = 0; t < 86400 - start; t += 30){
                        double up = stations.get(s - 1).getLast30sCount(t);
                        double down = stations.get(s).getLast30sCount(t);
                        boolean valid = up >= 0 && down >= 0;
                        double net = down - up;
                        for(Ramp r : direction_ramps){
                            if(r.segment == s && r.isMetered()){
                                double count = rampCount(read, r, t);
                                valid &= count >= 0;
                                net -= r.sign * count;
                            }
                        }
                        if(valid){
                            int bin = (int)((start + t) / Cell.PRIOR_BIN);
                            day_sum[bin] += net * 120; // veh/hr
                            day_n[bin]++;
                        }
                    }
                    addDay(day_sum, day_n, sum.get(direction)[s], days.get(direction)[s]);
                }

                for(int i = 0; i < direction_ramps.size(); i++){
                    Ramp r = direction_ramps.get(i);
                    if(!r.isMetered()){
                        continue;
                    }
                    double[] day_sum = new double[BINS];
                    int[] day_n = new int[BINS];
                    for(long t = 0; t < 86400 - start; t += 30){
                        double count = rampCount(read, r, t);
                        if(count >= 0){
                            int bin = (int)((start + t) / Cell.PRIOR_BIN);
                            day_sum[bin] += count * 120; // veh/hr
                            day_n[bin]++;
                        }
                    }
                    addDay(day_sum, day_n, metered_sum.get(direction)[i], metered_days.get(direction)[i]);
                }
            }
        }

        PrintStream out = new PrintStream(new File(args[0]));
        out.println("direction,type,lat,lon,osm_way,time,flow,days,metered");
        for(String direction : ramps.keySet()){
            List<Ramp> direction_ramps = ramps.get(direction);
            for(int i = 0; i < direction_ramps.size(); i++){
                Ramp ramp = direction_ramps.get(i);
                String[] c = ramp.columns;
                System.out.print(direction+" "+c[1]+" "+c[3]+(ramp.isMetered()? " metered" : " (segment "+ramp.segment+")")+":");

                for(int b = 0; b < BINS; b++){
                    String flow = "";
                    int n;
                    if(ramp.isMetered()){
                        n = metered_days.get(direction)[i][b];
                        if(n > 0){
                            flow = String.format("%.0f", metered_sum.get(direction)[i][b] / n);
                        }
                    }
                    else{
                        n = (ramp.segment > 0? days.get(direction)[ramp.segment][b] : 0);
                        if(n > 0){
                            double net = sum.get(direction)[ramp.segment][b] / n;
                            // unmetered ramps of the same segment with the matching sign
                            int matching = 0;
                            for(Ramp other : direction_ramps){
                                if(!other.isMetered() && other.segment == ramp.segment && other.sign * net > 0){
                                    matching++;
                                }
                            }
                            flow = String.format("%.0f", ramp.sign * net > 0? net / matching : 0);
                        }
                    }
                    if(!flow.isEmpty() && b % 4 == 0 && b >= 20 && b < 80){
                        System.out.print(" "+(b / 4)+"h "+flow);
                    }
                    out.println(direction+","+c[1]+","+c[2]+","+c[3]+","+c[4]+","+String.format("%02d:%02d", b * Cell.PRIOR_BIN / 3600, b * Cell.PRIOR_BIN % 3600 / 60)
                            +","+flow+","+n+","+ramp.isMetered());
                }
                System.out.println();
            }
        }
        out.close();
    }

    // sum of the 30-second counts of the loops of a metered ramp, or -1 if any loop has no data
    static double rampCount(ReadData read, Ramp r, long t){
        double total = 0;
        for(String loop : r.loops()){
            Detector det = read.getDetector(loop);
            double count = (det == null? -1 : det.getLast30sCount(t));
            if(count < 0){
                return -1;
            }
            total += count;
        }
        return total;
    }

    // adds one day's bin averages (veh/hr) to the sums over days, for bins with enough data
    static void addDay(double[] day_sum, int[] day_n, double[] sum, int[] days){
        for(int b = 0; b < BINS; b++){
            if(day_n[b] >= MIN_INTERVALS){
                sum[b] += day_sum[b] / day_n[b];
                days[b]++;
            }
        }
    }

    // ramps of each corridor with their positions and segments
    static Map<String, List<Ramp>> readRamps(Map<String, Corridor> corridors) throws IOException {
        Map<String, List<Ramp>> output = new HashMap<>();
        Map<String, List<Double>> station_positions = new HashMap<>();
        for(String direction : corridors.keySet()){
            output.put(direction, new ArrayList<>());
            List<Double> positions = new ArrayList<>();
            for(Cell c : corridors.get(direction).getCells()){
                if(c.hasDetector() && c.getDetector().getType() == Detector.Type.Mainline){
                    positions.add(corridors.get(direction).findPosition(c.getDetector().getLocation(), Corridor.MAX_RAMP_DISTANCE));
                }
            }
            station_positions.put(direction, positions);
        }

        Scanner filein = new Scanner(RAMPS);
        filein.nextLine();
        while(filein.hasNextLine()){
            String[] columns = filein.nextLine().split(",", -1);
            if(columns.length < 8 || !corridors.containsKey(columns[0])){
                continue;
            }
            Ramp r = new Ramp();
            r.columns = columns;
            r.sign = columns[1].equals("Merge")? 1 : -1;
            r.position = corridors.get(columns[0]).findPosition(new Coordinate(Double.parseDouble(columns[2]), Double.parseDouble(columns[3])), 100);
            r.segment = -1;
            List<Double> positions = station_positions.get(columns[0]);
            for(int s = 1; s < positions.size(); s++){
                if(r.position > positions.get(s - 1) && r.position <= positions.get(s)){
                    r.segment = s;
                }
            }
            output.get(columns[0]).add(r);
        }
        filein.close();
        return output;
    }
}
