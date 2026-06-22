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
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.regex.Pattern;
import umn.pw_estimation.PW.Coordinate;
import umn.pw_estimation.PW.Corridor;
import umn.pw_estimation.PW.Link;

/**
 *
 * @author michael
 */
public class ReadData {
    
    private Map<String, HistoricalDetector> saved_detectors;
    
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
        while(filein.hasNextLine()){
            line1 = filein.nextLine();
            split1 = line1.split(",");
            
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
        
        saved_detectors = new HashMap<>();
        double K = 5280.0/AVG_VEH_LEN;
                
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
                
                double v = getFFSpeed(speed_limit);
                double Q = getCapacity(v);
                double w = getW(v);
                
                Link link = new Link(""+id, coords, dt, v, v, Q, w, K, lanes);
                
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
        
        Map<String, List<Detector>> detectors = new HashMap<>();
        
        while(filein.hasNext()){
            String[] columns = filein.nextLine().split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
            
            if(columns.length == 0){
                break;
            }

            
            String name = columns[2];
            
            String typename = columns[11];
          
            String location = columns[9];
            String loc_name = location+"-"+typename;
            
            
            int lane = Integer.parseInt(columns[10]);
            

            
            double lat = Double.parseDouble(columns[15]);
            double lon = Double.parseDouble(columns[16]);
            
            HistoricalDetector.Type type = HistoricalDetector.getType(typename);
            
            if(type != null){
                HistoricalDetector loop = new HistoricalDetector(name, new Coordinate(lat, lon), type);
                
                // I am assuming that the string location in the CSV file is different for each location (mainline, on-ramp, off-ramp)
                if(!detectors.containsKey(loc_name)){
                    detectors.put(loc_name, new ArrayList<>());
                }
                
                detectors.get(loc_name).add(loop);
                saved_detectors.put(loop.getName(), loop);
            }
            
            filein.nextLine();
        }
        
        for(String name : detectors.keySet()){
            DetectorGroup group = new DetectorGroup(name, detectors.get(name));
            
            for(int i = 0; i < detector_labels.length; i++){
                if(name.indexOf(detector_labels[i]) >= 0){
                    boolean success = output.get(labels[i]).addDetector(group);
                    
                    if(success){
                        System.out.println("added detector "+name+" - "+group);
                    }
                    else{
                        System.out.println("failed to add detector "+group);
                    }
                    
                    break;
                }
            }
        }
        
        System.out.println("Found detectors: "+saved_detectors.keySet());
        
        
        return output; 
    }
    
    static public final double AVG_VEH_LEN = 27.6;
    
    private static double getCapacity(double ffspeed){

        //HCM
        return Math.min(2400, 2200 + 10 * (ffspeed - 50));
    }
    public static double getW(double ffspeed){
        return ffspeed/2;
    }

    private static double getFFSpeed(double speed_limit){
        return speed_limit + 5;
    }
}
