/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.Input;

import java.util.List;
import umn.pw_estimation.PW.Coordinate;

/**
 *
 * @author mlevin
 */
public class DetectorGroup extends Detector{
    private Detector[] detectors;
    
    public DetectorGroup(Detector[] detectors){
        super(createName(detectors), findType(detectors));
        this.detectors = detectors;
    }
    
    public DetectorGroup(String name, List<Detector> list){
        super(createName(list), findType(list));
        this.detectors = new Detector[list.size()];
        
        for(int i = 0; i < list.size(); i++){
            detectors[i] = list.get(i);
        }
    }
    
    private static Type findType(List<Detector> list){
        Type type = list.get(0).getType();
        
        for(int idx = 1; idx < list.size(); idx++){
            if(type != list.get(idx).getType()){
                System.out.println(list);
                throw new RuntimeException("type mismatch in detector group");
            }
        }
        
        return type;
    }
    
    private static Type findType(Detector[] list){
        Type type = list[0].getType();
        
        for(int idx = 1; idx < list.length; idx++){
            if(type != list[idx].getType()){
                System.out.println(list);
                throw new RuntimeException("type mismatch in detector group");
            }
        }
        
        return type;
    }
    
    public Coordinate getLocation(){
        // locations should be very close - within 60 feet
        return detectors[0].getLocation();
    }
    
    public double getLast30sCount(long t){
        int output = 0;
        int good = 0;
        

        for(Detector d : detectors){
            double d_out = d.getLast30sCount(t);
            
            if(d_out >= 0){
                output += d_out;
                good ++;
            }
        }
        
        if(good == 0){
            return -1;
        }
        else{
            return output * (double) detectors.length/good;
        }
    }
    
    public double getLast30sSpeed(long t){
        double output = 0;
        int weight = 0;
        int good = 0;
        
        for(Detector d : detectors){
            double d_speed = d.getLast30sSpeed(t);
            
            if(d_speed >= 0){
                double d_count = d.getLast30sCount(t);
                output += d_speed * d_count;
                weight += d_count;
                good ++;
            }
        }
        
        if(good == 0 || weight == 0){
            return -1;
        }
        else{
            return output/weight;
        }
    }
    
    public static String createName(Detector... detectors){
        String output = "[";
        
        for(int i = 0; i < detectors.length; i++){
            output += detectors[i].getName();
            
            if(i < detectors.length-1){
                output += ", ";
            }
        }
        output += "]";
        
        return output;
    }
    
    
    public static String createName(List<Detector> detectors){
        String output = "[";
        
        for(int i = 0; i < detectors.size(); i++){
            output += detectors.get(i).getName();
            
            if(i < detectors.size()-1){
                output += "+";
            }
        }
        output += "]";
        
        return output;
    }
   
}
