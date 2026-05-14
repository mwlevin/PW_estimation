/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.Input;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import umn.pw_estimation.PW.Coordinate;

/**
 *
 * @author mlevin
 */
public class HistoricalDetector extends Detector {
    
    private Coordinate loc;
    
    public List<Double> speeds;
    public List<Integer> counts;
    
    public HistoricalDetector(String name, Coordinate loc){
        super(name);
        this.loc = loc;
        
        counts = new ArrayList<>();
        speeds = new ArrayList<>();
    }
    
    public Coordinate getLocation(){
        return loc;
    }
    
    public int getLast30sCount(long t){
        int idx = (int)(t/30);
        if(idx < counts.size()){
            int output = counts.get(idx);
            if(output >= 0){
                return output;
            }
            
        }

        return 0;
        
    }
    
    public double getLast30sSpeed(long t){
        int idx = (int)(t/30);
        if(idx < counts.size()){
            int output = counts.get(idx);
            if(output >= 0){
                return output;
            }
        }
        return 65;
        
    }
}
