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
 * Historical average flows of the unmetered ramps on MN-610, by time of day, from the flow balance between consecutive
 * mainline stations: downstream station flow - upstream station flow - metered entrance flows + metered exit flows.
 * Stations and ramps are ordered by their position along the corridor (the station's first loop, and the ramp's merge or
 * diverge point), not by cell, because a ramp in the same cell as a station can be on either side of it.
 * The net flow of each segment is given to the unmetered ramps in it whose type (entrance or exit) matches its sign, split
 * equally. Ramps upstream of the first station or downstream of the last have no historical average.
 * Read by ReadData.readUnmeteredPrior().
 *
 * Usage: UnmeteredPrior <output CSV> <detector data CSV> [detector data CSV ...]
 * Each data file is one day (e.g. 5 AM-8 PM from tools/mndot_extract/extract.sh). Do not include the days used for validation.
 *
 * @author mlevin
 */
public class UnmeteredPrior {

    static final File RAMPS = new File("data/MN610/ramps.csv");
    static final int BINS = 86400 / Cell.PRIOR_BIN;

    // a 30-second interval counts only if all detectors of the segment have data; a day's bin counts if at least half of
    // its intervals do
    static final int MIN_INTERVALS = Cell.PRIOR_BIN / 30 / 2;

    // a ramp from the ramps file
    static class Ramp {
        String[] columns;
        double position; // m from the start of the corridor
        int sign; // 1 entrance, -1 exit
        int segment; // index of the downstream station of its segment, or -1
    }

    public static void main(String[] args) throws IOException {
        // per direction, per segment (index of its downstream station), per bin: sum over days of the day's average, and number of days
        Map<String, double[][]> sum = new HashMap<>();
        Map<String, int[][]> days = new HashMap<>();
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
                sum.putIfAbsent(direction, new double[stations.size()][BINS]);
                days.putIfAbsent(direction, new int[stations.size()][BINS]);

                for(int s = 1; s < stations.size(); s++){
                    double[] day_sum = new double[BINS];
                    int[] day_n = new int[BINS];

                    for(long t = 0; t < 86400 - start; t += 30){
                        double up = stations.get(s - 1).getLast30sCount(t);
                        double down = stations.get(s).getLast30sCount(t);
                        boolean valid = up >= 0 && down >= 0;
                        double net = down - up;
                        for(Ramp r : ramps.get(direction)){
                            if(r.segment == s && !r.columns[7].trim().isEmpty()){
                                for(String loop : r.columns[7].trim().split(" ")){
                                    Detector det = read.getDetector(loop);
                                    double count = (det == null? -1 : det.getLast30sCount(t));
                                    valid &= count >= 0;
                                    net -= r.sign * count;
                                }
                            }
                        }
                        if(valid){
                            int bin = (int)((start + t) / Cell.PRIOR_BIN);
                            day_sum[bin] += net * 120; // veh/hr
                            day_n[bin]++;
                        }
                    }
                    for(int b = 0; b < BINS; b++){
                        if(day_n[b] >= MIN_INTERVALS){
                            sum.get(direction)[s][b] += day_sum[b] / day_n[b];
                            days.get(direction)[s][b]++;
                        }
                    }
                }
            }
        }

        PrintStream out = new PrintStream(new File(args[0]));
        out.println("direction,type,lat,lon,osm_way,time,flow,days");
        for(String direction : ramps.keySet()){
            for(Ramp ramp : ramps.get(direction)){
                if(!ramp.columns[7].trim().isEmpty()){
                    continue;
                }
                String[] c = ramp.columns;
                System.out.print(direction+" "+c[1]+" "+c[3]+" (segment "+ramp.segment+"):");

                for(int b = 0; b < BINS; b++){
                    String flow = "";
                    int n = (ramp.segment > 0? days.get(direction)[ramp.segment][b] : 0);
                    if(n > 0){
                        double net = sum.get(direction)[ramp.segment][b] / n;
                        // unmetered ramps of the same segment with the matching sign
                        int matching = 0;
                        for(Ramp other : ramps.get(direction)){
                            if(other.columns[7].trim().isEmpty() && other.segment == ramp.segment && other.sign * net > 0){
                                matching++;
                            }
                        }
                        flow = String.format("%.0f", ramp.sign * net > 0? net / matching : 0);
                        if(b % 4 == 0 && b >= 20 && b < 80){
                            System.out.print(" "+(b / 4)+"h "+flow);
                        }
                    }
                    out.println(direction+","+c[1]+","+c[2]+","+c[3]+","+c[4]+","+String.format("%02d:%02d", b * Cell.PRIOR_BIN / 3600, b * Cell.PRIOR_BIN % 3600 / 60)+","+flow+","+n);
                }
                System.out.println();
            }
        }
        out.close();
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
