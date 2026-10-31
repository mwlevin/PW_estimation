/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.Input;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.regex.Pattern;
import umn.pw_estimation.PW.Cell;
import umn.pw_estimation.PW.Coordinate;
import umn.pw_estimation.PW.Corridor;
import umn.pw_estimation.PW.Link;

/**
 *
 * @author michael
 */
public class ReadData {
    
    private Map<String, HistoricalDetector> saved_detectors;
    
    // corridors made by the last readOSM()
    private Map<String, Corridor> corridors = new HashMap<>();
    
    // time of day (seconds after midnight) at the start of the detector data read by readDetectorData()
    private long start_of_day;
    
    public long getStartOfDay(){
        return start_of_day;
    }
    
    // the detector (loop) with this name, or null if it is not in the detector inventory
    public Detector getDetector(String name){
        return saved_detectors.get(name);
    }
    
    public ReadData(){
        
    }
    
    public void readDetectorData(File file) throws IOException{
        // expect a format of speed volume in separate columns per detector
        // column 1: empty
        // column 2: time
        // column 3: speed or volume
        // row 1: detector name
        // row 2: label (speed / volume)
        // row 3: date
        // rows afterwards: one per 30sec, starting at earliest time
        
        Map<String, Integer[]> columns = new HashMap<>(); // per detector; speed and volume columns
        
        Scanner filein = new Scanner(file);
        filein.useDelimiter(Pattern.compile("(\\n)|,")); // CSV file
        
        String line1 = filein.nextLine();
        
        String line2 = filein.nextLine();
        
        String[] split1 = line1.split(",");
        String[] split2 = line2.split(",");
        
        for(int c = 0; c < split1.length; c++){
            if(split1[c].trim().length() > 0){
                
                String type = split2[c].trim();
                String id = split1[c].trim();
                
                
                if(type.equals("Speed")){
                    if(!columns.containsKey(id)){
                       columns.put(id, new Integer[]{-1, -1}); 
                    }
                    
                    columns.get(id)[0] = c;
                }
                else if(type.equals("Volume")){
                    if(!columns.containsKey(id)){
                       columns.put(id, new Integer[]{-1, -1}); 
                    }
                    
                    columns.get(id)[1] = c;
                }
            }
        }
        
        filein.nextLine();
        boolean first = true;
        while(filein.hasNextLine()){
            line1 = filein.nextLine();
            split1 = line1.split(",");
            
            // the time column of the first row is the end of the first 30 seconds, e.g. 3:00:30 PM for data starting at 3 PM
            if(first){
                start_of_day = parseClock(split1[1].trim()) - 30;
                for(Corridor c : corridors.values()){
                    c.setClockStart(start_of_day);
                }
                first = false;
            }
            
            for(String id : columns.keySet()){
                int count = (int)Double.parseDouble(split1[columns.get(id)[1]].trim());
                double speed = Double.parseDouble(split1[columns.get(id)[0]].trim());
                
                // it is possible to give data for a detector that is not being used (e.g. passage instead of merge). Ignore it.
                if(saved_detectors.containsKey(id)){
                    saved_detectors.get(id).addPoint(count, speed);
                    
                }
            }
        }
        
        filein.close();
    }
    
    public Map<String, Corridor> readOSM(String corridor_name, File osm_file, double dt, String[] labels, File detectors_file, String[] detector_labels) throws IOException {
        return readOSM(corridor_name, osm_file, dt, labels, detectors_file, detector_labels, null);
    }
    
    // ramps_file (optional) lists the entrance and exit ramps from OpenStreetMap, with the ramp detector loops assigned to each
    // (made by tools/ramps/make_ramps.py). With it, each ramp's flow enters or leaves the corridor at the ramp's merge or
    // diverge point, and a ramp without detectors is an unmetered ramp, whose flow the Kalman filter estimates. Without it,
    // ramp detectors are grouped by location name and placed at the nearest point of the corridor.
    public Map<String, Corridor> readOSM(String corridor_name, File osm_file, double dt, String[] labels, File detectors_file, String[] detector_labels,
            File ramps_file) throws IOException {
        
        saved_detectors = new HashMap<>();

        Map<String, List<Link>> links = new HashMap<>();
        
        for(String l : labels){
            links.put(l, new ArrayList<>());
        }
        
        
        Scanner filein = new Scanner(osm_file);
        
        while(filein.hasNextLine()){
            if(filein.nextLine().trim().equals("\"type\": \"way\",")){
                
                
                String temp = filein.nextLine();
                int id = Integer.parseInt(temp.substring(temp.indexOf(":")+1, temp.indexOf(",")).trim());
                
                while(filein.nextLine().indexOf("geometry") < 0);
                
                List<Coordinate> coords = new ArrayList<>();
                
                
                while(true){
                    temp = filein.nextLine();
                    
                    if(temp.indexOf("lat") < 0){
                        break;
                    }
                    
                    double lat = Double.parseDouble(temp.substring(temp.indexOf(":")+1, temp.indexOf(",")).trim());
                    temp = temp.substring(temp.indexOf(",")+1);
                    double lon = Double.parseDouble(temp.substring(temp.indexOf(":")+1, temp.indexOf("}")).trim());
                    
                    coords.add(new Coordinate(lat, lon));
                }
                
                while(filein.nextLine().indexOf("tags") < 0);
                
                Map<String, String> tags = new HashMap<>();
                while(true){
                    temp = filein.nextLine();
                    if(temp.indexOf(":") < 0){
                        break;
                    }
                    
                    String key = temp.substring(0, temp.indexOf("\":")+1);
                    
                    key = key.substring(key.indexOf("\"")+1);
                    
                    key = key.substring(0, key.indexOf("\"")).trim();
                    
                    String value = temp.substring(temp.indexOf(":")+1);
                    value = value.substring(value.indexOf("\"")+1);
                    value = value.substring(0, value.indexOf("\"")).trim();
                    
                    tags.put(key, value);
                }
                
                int lanes = tags.containsKey("lanes")? Integer.parseInt(tags.get("lanes")) : 3;
                
                if(LANE_CORRECTIONS.containsKey(id)){
                    lanes = LANE_CORRECTIONS.get(id);
                }
                
                int speed_limit = 65;
                
                if(tags.containsKey("maxspeed")){
                    temp = tags.get("maxspeed");
                    temp = temp.substring(0, temp.indexOf("mph")).trim();
                    speed_limit = Integer.parseInt(temp);
                }

                String label = null;
                for(String l : labels){
                    if(tags.containsKey("description") && tags.get("description").indexOf(l) >= 0){
                        label = l;
                        break;
                    }
                }
                
                // some links are not labeled!
                if(label == null){
                    // this is compass rose direction not standard polar coordinates
                    double direction = Coordinate.bearing(coords.get(0), coords.get(coords.size()-1));
                    for(String l : labels){
                        
                        if((l.indexOf("WB") >= 0 || l.indexOf("westbound") >= 0) && (direction > 180 && direction < 360)){
                            label = l;
                        }
                        else if((l.indexOf("EB") >= 0 || l.indexOf("eastbound") >= 0) && (direction > 0 && direction < 180)){
                            label = l;
                        }
                        if((l.indexOf("NB") >= 0 || l.indexOf("northbound") >= 0) && (direction > 270 || direction < 90)){
                            label = l;
                        }
                        if((l.indexOf("SB") >= 0 || l.indexOf("southbound") >= 0) && (direction > 90 && direction < 270)){
                            label = l;
                        }
                    }
                }
                
                // one free flow speed for the whole road (see FREE_FLOW_SPEED below); speed_limit is not used
                double v = FREE_FLOW_SPEED;
                double w = WAVE_SPEED;
                double Q = CAPACITY;
                double Qd = DISCHARGE_RATE;

                Link link = new Link(""+id, coords, v, v + MAX_SPEED_MARGIN, Q, Qd, w, JAM_DENSITY, lanes);
                
                if(label != null){
                    links.get(label).add(link);
                }
                else{
                    System.out.println("Skipping OSM link"+id+" - no direction found");
                }
            }
        }
        
        filein.close();
        
        Map<String, Corridor> output = new TreeMap<>();
        
        for(String l : labels){
            output.put(l, new Corridor(corridor_name+" "+l, links.get(l), dt));
        }
        
        filein = new Scanner(detectors_file);
        
        filein.nextLine();
        
        List<String> lines = new ArrayList<>();
        while(filein.hasNextLine()){
            lines.add(filein.nextLine());
        }
        filein.close();
        
        // locations with a merge detector; elsewhere, entrance ramps are counted by their passage and bypass detectors
        Set<String> has_merge = new HashSet<>();
        for(String line : lines){
            String[] columns = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            if(columns.length > 11 && columns[11].equals("Merge")){
                has_merge.add(columns[9]);
            }
        }
        
        Map<String, List<Detector>> detectors = new HashMap<>();
        
        for(String line : lines){
            
            String[] columns = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

            
            if(columns.length == 0){
                break;
            }

            
            String name = columns[2];
            
            String typename = columns[11];
            
            String location = columns[9];
            
            if(AUXILIARY_EXITS.contains(name)){
                typename = "Exit";
            }
            else if(typename.equals("Auxiliary")){
                typename = "Mainline";
            }
            
            // most metered entrance ramps have no merge detector; passage detectors count the vehicles passing the ramp
            // meter, and bypass detectors those in the (HOV) bypass lane, so together they count the ramp flow
            // queue detectors are not used: they count vehicles arriving at the back of the meter queue, not entering the freeway
            if((typename.equals("Passage") || typename.equals("Bypass")) && !has_merge.contains(location)){
                typename = "Merge";
            }

            String loc_name = location+"-"+typename;
            
            // mainline loops are grouped by MnDOT station ID, because some location names cover two stations about
            // 0.5 mi apart (e.g. "T.H.610 WB/T.H.252" is stations S1790 and S2052), and the loops of a group must be at the same place
            // ramp detectors are still grouped by location name, so all loops of an interchange ramp are added together
            String station = columns[3].trim();
            if(typename.equals("Mainline") && station.length() > 0){
                loc_name = location+"-"+station+"-"+typename;
            }
            
            
            int lane = Integer.parseInt(columns[10]);
            
            double lat = Double.parseDouble(columns[15]);
            double lon = Double.parseDouble(columns[16]);
            
            HistoricalDetector.Type type = HistoricalDetector.getType(typename);
            
            // some loops are listed twice in the inventory (e.g. 8271); a repeated loop would be counted twice in its station
            if(type != null && saved_detectors.containsKey(name)){
                System.out.println("Skipping repeated detector "+name);
            }
            else if(type != null){
                HistoricalDetector loop = new HistoricalDetector(name, new Coordinate(lat, lon), type);
                
                // I am assuming that the string location in the CSV file is different for each location (mainline, on-ramp, off-ramp)
                if(!detectors.containsKey(loc_name)){
                    detectors.put(loc_name, new ArrayList<>());
                }
                
                detectors.get(loc_name).add(loop);
                saved_detectors.put(loop.getName(), loop);
            }
            
        }
        
        for(String name : detectors.keySet()){
            // with a ramps file, ramp detectors are added below
            if(ramps_file != null && !name.endsWith("-Mainline")){
                continue;
            }
            
            DetectorGroup group = new DetectorGroup(name, detectors.get(name));
            
            for(int i = 0; i < detector_labels.length; i++){
                if(name.indexOf(detector_labels[i]) >= 0){
                    boolean success = output.get(labels[i]).addDetector(group);
                    
                    if(success){
                        System.out.println("added detector "+name+" - "+group+" "+group.getType());
                    }
                    else{
                        System.out.println("failed to add detector "+group);
                    }
                    
                    break;
                }
            }
        }
        
        System.out.println("Found detectors: "+saved_detectors.keySet());
        
        if(ramps_file != null){
            readRamps(ramps_file, output);
        }
        
        
        corridors = output;
        return output; 
    }
    
    // seconds after midnight of a time such as 3:00:30 PM, or 15:00:30 without AM/PM
    static long parseClock(String clock){
        String[] parts = clock.split("[: ]");
        long hour = Long.parseLong(parts[0]);
        if(parts.length > 3){
            hour = hour % 12 + (parts[3].equalsIgnoreCase("PM")? 12 : 0);
        }
        return hour * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2]);
    }
    
    // reads the historical average flows of unmetered ramps made by UnmeteredPrior; columns: direction, type, lat, lon,
    // osm_way, time (HH:MM, start of a bin of Cell.PRIOR_BIN seconds), flow (veh/hr, positive entering, empty if unknown)
    // must be called after readOSM() with the ramps file
    public void readUnmeteredPrior(File file) throws IOException {
        Map<String, double[]> profiles = new HashMap<>();
        Map<String, String[]> ramps = new HashMap<>();
        Scanner filein = new Scanner(file);
        filein.nextLine();
        while(filein.hasNextLine()){
            String[] columns = filein.nextLine().split(",", -1);
            if(columns.length < 7){
                continue;
            }
            String key = columns[0]+" "+columns[4];
            if(!profiles.containsKey(key)){
                double[] profile = new double[86400 / Cell.PRIOR_BIN];
                Arrays.fill(profile, Double.NaN);
                profiles.put(key, profile);
                ramps.put(key, columns);
            }
            int bin = (int)(parseClock(columns[5]+":00") / Cell.PRIOR_BIN);
            if(!columns[6].isEmpty()){
                profiles.get(key)[bin] = Double.parseDouble(columns[6]) / 3600;
            }
        }
        filein.close();
        
        int count = 0;
        for(String key : profiles.keySet()){
            String[] columns = ramps.get(key);
            Corridor corridor = corridors.get(columns[0]);
            if(corridor != null && corridor.addUnmeteredPrior(new Coordinate(Double.parseDouble(columns[2]), Double.parseDouble(columns[3])), profiles.get(key))){
                count++;
            }
        }
        System.out.println("historical averages for "+count+" unmetered ramps");
    }
    
    // reads the ramps file (see readOSM()); columns: direction, type (Merge or Exit), lat, lon, osm_way, location,
    // detector_distance_ft, loops (separated by spaces; empty for an unmetered ramp)
    private void readRamps(File ramps_file, Map<String, Corridor> corridors) throws IOException {
        Scanner filein = new Scanner(ramps_file);
        filein.nextLine();
        int metered = 0, unmetered = 0;
        
        while(filein.hasNextLine()){
            String[] columns = filein.nextLine().split(",", -1);
            if(columns.length < 8 || !corridors.containsKey(columns[0])){
                continue;
            }
            Corridor corridor = corridors.get(columns[0]);
            boolean entrance = columns[1].equals("Merge");
            Coordinate point = new Coordinate(Double.parseDouble(columns[2]), Double.parseDouble(columns[3]));
            
            List<Detector> loops = new ArrayList<>();
            for(String loop : columns[7].trim().split(" ")){
                if(saved_detectors.containsKey(loop)){
                    loops.add(saved_detectors.get(loop));
                }
            }
            
            Detector group = loops.isEmpty()? null : new DetectorGroup(columns[0]+" ramp "+columns[4], loops);
            if(!corridor.addRamp(point, entrance, group)){
                System.out.println("failed to add ramp "+columns[4]+" ("+columns[1]+") to "+corridor);
            }
            else if(group == null){
                unmetered++;
            }
            else{
                metered++;
            }
        }
        filein.close();
        System.out.println("added "+metered+" ramps with detectors and "+unmetered+" unmetered ramps");
    }

    // corrections to the number of lanes in OpenStreetMap, by way ID
    // way 759272326 (610 WB from Maple Grove Pkwy to I-94) is tagged with 1 lane, but both detector stations on it have
    // loops in lanes 1 and 2. With 1 lane, its capacity was too low and created a false queue upstream.
    static public final Map<Integer, Integer> LANE_CORRECTIONS = Map.of(759272326, 2);
    
    // auxiliary-lane loops that count exiting traffic, used as exit ramp detectors
    // At 610 WB/West River Rd (station S2051), the two auxiliary lanes carry about 1,770 veh/hr at 55-60 mi/hr while the
    // mainline lanes are congested, and they end before T.H. 252; about 1,600 veh/hr leave between this station and the
    // next one, where there is no exit ramp detector (9/2/26 3-6 PM).
    static public final Set<String> AUXILIARY_EXITS = Set.of("8249", "8250");

    // triangular flow-density relationship, per lane; heavy vehicles are not converted to passenger cars
    // The free flow speed is measured with the Highway Capacity Manual (HCM) field method. The congested branch (wave speed and
    // jam density) is fitted to the detector data, because the HCM values (capacity 2390 veh/hr/ln and jam density 190 veh/mi/ln)
    // overestimate flows in congestion on MN-610: at 60-70 veh/mi/ln, the HCM triangle gives about 1900 veh/hr/ln, while
    // the measured median is 1320 veh/hr/ln. With the HCM values, the estimated density inside queues was much too high.
    // Least-squares line through the 30-second mainline measurements with density 45-150 veh/mi/ln (13,701 measurements) on
    // 10 weekdays (Tuesday-Thursday, 9/1-9/24/26, 5 AM-8 PM, both directions), not including the validation days 9/2 and 9/17.
    static public double JAM_DENSITY = 172;  // veh/mi per lane
    static public double WAVE_SPEED = 11.9;  // mi/hr; congested (backward) wave speed

    // mi/hr; free flow speed for the whole road, measured from the detector data with the HCM field method: the average
    // 30-second mainline speed when flow is below 1000 veh/hr/ln. Speeds above 87 mph (upper quartile + 1.5 x interquartile
    // range, 2.3% of samples) were excluded as unusually fast drivers. Both directions, 4/8/26 9-10 AM.
    static public double FREE_FLOW_SPEED = 69;
    static public double MAX_SPEED_MARGIN = 10;  // mi/hr; estimated speeds may exceed the free flow speed by this much, since measured speeds do

    // capacity (veh/hr/ln) before traffic breaks down; the flow per lane is FREE_FLOW_SPEED * k up to the critical density
    // CAPACITY / FREE_FLOW_SPEED
    static public double CAPACITY = 2200;
    
    // queue discharge rate (veh/hr/ln): the largest flow in congestion, which is min(DISCHARGE_RATE, WAVE_SPEED * (JAM_DENSITY - k))
    static public double DISCHARGE_RATE = 2200;


}
