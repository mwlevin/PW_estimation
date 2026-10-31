package umn.pw_estimation;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import umn.pw_estimation.Input.ReadData;
import umn.pw_estimation.PW.Coordinate;
import umn.pw_estimation.PW.Corridor;
import umn.pw_estimation.PW.DrawOSM;

/**
 * Draws maps of the estimated speed and density on both directions of MN-610 at chosen times, using all detectors.
 *
 * Usage: Figures <detector data CSV> <clock time of the first row, HH:MM> <output folder> <HH:MM> [HH:MM ...]
 * The first row of the data is the 30 seconds after the given clock time (e.g. 15:00 for a first row ending 3:00:30 PM).
 * For each direction and time, saves <direction>_<speed|density>_<HHMM>.png, and also writes <direction> estimate.csv.
 *
 * @author mlevin
 */
public class Figures {

    // cross streets labeled on the maps
    static final String[] LABELS = {"I-94", "Hemlock Ln", "U.S.169", "W Broadway Ave", "Noble Pkwy", "T.H.252", "East River Rd", "University Ave"};

    public static void main(String[] args) throws IOException {
        File data = new File(args[0]);
        int start = toSeconds(args[1]);
        File folder = new File(args[2]);
        folder.mkdirs();

        double dt = 3;
        ReadData read = new ReadData();
        Map<String, Corridor> corridors = read.readOSM("610", new File("data/MN610/geometry.txt"), dt, new String[]{"WB", "EB"},
                new File("data/MN610/MN610_Edited.csv"), new String[]{"T.H.610 WB", "T.H.610 EB"}, new File("data/MN610/ramps.csv"));
        read.readDetectorData(data);
        // historical average flows of the ramps without detectors, made by UnmeteredPrior
        read.readUnmeteredPrior(new File("data/MN610/unmetered_prior.csv"));

        int end = 0;
        for(int i = 3; i < args.length; i++){
            end = Math.max(end, toSeconds(args[i]) - start + 30);
        }

        Map<String, Coordinate> places = readPlaces(new File("data/MN610/MN610_Edited.csv"));

        for(String direction : corridors.keySet()){
            Corridor corridor = corridors.get(direction);
            corridor.init(0);
            corridor.estimate(end, new PrintStream(new FileOutputStream(new File(folder, direction+" estimate.csv"))));

            String name = direction.equals("EB")? "eastbound" : "westbound";

            for(int i = 3; i < args.length; i++){
                long time = toSeconds(args[i]) - start;
                for(DrawOSM.DISPLAY_MODE mode : new DrawOSM.DISPLAY_MODE[]{DrawOSM.DISPLAY_MODE.SPEED, DrawOSM.DISPLAY_MODE.DENSITY}){
                    DrawOSM map = new DrawOSM(java.util.List.of(corridor), mode);
                    map.setDrawData(corridor.getData(time));
                    map.setTitle("MN-610 "+name+", "+clock(args[i])+", estimated "+(mode == DrawOSM.DISPLAY_MODE.SPEED? "speed" : "density"));
                    for(String label : LABELS){
                        Coordinate loc = places.get("T.H.610 "+direction+"/"+label);
                        if(loc != null){
                            map.addLabel(loc, label.replace("U.S.169", "U.S. 169").replace("T.H.252", "T.H. 252"));
                        }
                    }
                    map.saveImage(new File(folder, direction+"_"+mode.toString().toLowerCase()+"_"+args[i].replace(":", "")).getPath());
                }
            }
        }
    }

    // location of the first mainline loop listed for each location name in the detector inventory
    static Map<String, Coordinate> readPlaces(File file) throws IOException {
        Map<String, Coordinate> output = new HashMap<>();
        Scanner in = new Scanner(file);
        in.nextLine();
        while(in.hasNextLine()){
            String[] columns = in.nextLine().split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            if(columns.length > 16 && columns[11].equals("Mainline") && !output.containsKey(columns[9])){
                output.put(columns[9], new Coordinate(Double.parseDouble(columns[15]), Double.parseDouble(columns[16])));
            }
        }
        in.close();
        return output;
    }

    static int toSeconds(String hhmm){
        String[] parts = hhmm.split(":");
        return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60;
    }

    // e.g. 16:15 -> 4:15 PM
    static String clock(String hhmm){
        String[] parts = hhmm.split(":");
        int hour = Integer.parseInt(parts[0]);
        return (hour % 12 == 0? 12 : hour % 12)+":"+parts[1]+(hour < 12? " AM" : " PM");
    }
}
