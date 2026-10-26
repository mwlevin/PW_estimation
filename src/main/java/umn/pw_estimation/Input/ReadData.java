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
                
                // one free flow speed for the whole road (see FREE_FLOW_SPEED below); speed_limit is not used
                double v = FREE_FLOW_SPEED;
                double Q = getCapacity(v);
                
                // the HCM does not give a congested wave speed; for a triangular flow-density relationship, the congested
                // branch goes from capacity (at density Q/v) to jam density, so w = Q / (K - Q/v), about 15.4 mph
                double w = Q / (JAM_DENSITY - Q / v);

                Link link = new Link(""+id, coords, v, v + MAX_SPEED_MARGIN, Q, w, JAM_DENSITY, lanes);
                
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
            String line = filein.nextLine();
            
            String[] columns = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");

            
            if(columns.length == 0){
                break;
            }

            
            String name = columns[2];
            
            String typename = columns[11];
            
            if(typename.equals("Auxiliary")){
                typename = "Mainline";
            }
          
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
            
        }
        
        for(String name : detectors.keySet()){
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
        
        
        return output; 
    }
    
    // triangular flow-density relationship with parameters from the HCM (6th edition) for basic freeway segments, per lane
    // heavy vehicles are not converted to passenger cars
    // the HCM's density at capacity (45 veh/mi) is not used: a triangle with this free flow speed and capacity reaches capacity at 34.6 veh/mi
    static public final double JAM_DENSITY = 190;  // veh/mi; jam density used in HCM oversaturated analysis

    // mi/hr; free flow speed for the whole road, measured from the detector data with the HCM field method: the average
    // 30-second mainline speed when flow is below 1000 veh/hr/ln. Speeds above 87 mph (upper quartile + 1.5 x interquartile
    // range, 2.3% of samples) were excluded as unusually fast drivers. Both directions, 4/8/26 9-10 AM.
    // The HCM capacity equation below is valid for 55-75 mph.
    static public double FREE_FLOW_SPEED = 69;
    static public double MAX_SPEED_MARGIN = 10;  // mi/hr; estimated speeds may exceed the free flow speed by this much, since measured speeds do

    // HCM capacity (veh/hr/ln) of a basic freeway segment for the given free flow speed (mph)
    private static double getCapacity(double ffspeed){

        //HCM
        return Math.min(2400, 2200 + 10 * (ffspeed - 50));
    }


}
