/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.Input;

/**
 *
 * @author mlevin
 */
public class DetectorGroup extends Detector{
    private Detector[] detectors;
    
    public DetectorGroup(Detector[] detectors){
        super(createName(detectors));
        this.detectors = detectors;
    }
    
    public int getLast30sCount(){
        int output = 0;
        for(Detector d : detectors){
            output += d.getLast30sCount();
        }
        return output;
    }
    
    public double getLast30sSpeed(){
        double output = 0;
        int weight = 0;
        for(Detector d : detectors){
            output += d.getLast30sSpeed();
            weight += d.getLast30sCount();
        }
        return output/weight;
    }
    
    public static String createName(Detector[] detectors){
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
}
