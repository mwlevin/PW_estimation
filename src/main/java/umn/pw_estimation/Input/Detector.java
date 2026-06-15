/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.Input;

import umn.pw_estimation.PW.Coordinate;

/**
 *
 * @author mlevin
 */
public abstract class Detector {
    
    public static enum Type{
        Mainline,
        Merge,
        Exit
    }
    
    public static Type getType(String name){
        if(name.equalsIgnoreCase("Mainline")){
            return Type.Mainline;
        }
        else if(name.equalsIgnoreCase("Merge")){
            return Type.Merge;
        }
        else if(name.equalsIgnoreCase("Exit")){
            return Type.Exit;
        }
        else{
            return null;
        }
    }
    
    
    
    private String name;
    private Type type;
    
    public Detector(String name, Type type){
        this.name = name;
        this.type = type;
    }
    
    public abstract Coordinate getLocation();
    
    
    public String getName(){
        return name;
    }
    
    public Type getType(){
        return type;
    }
    
    public String toString(){
        return name;
    }
    
    // every count is an int. But extrapolation for missing detectors may be a double.
    public abstract double getLast30sCount(long t);
    
    public double getLast30sFlow(long t){
        double count = getLast30sCount(t);
                
        if(count >= 0){
            return count / 30.0;
        }
        else{
            return -1;
        }
    }
    public abstract double getLast30sSpeed(long t);
    
    public double getLast30sDensity(long t){
        double flow = getLast30sFlow(t);
        double speed = getLast30sSpeed(t);
        
        if(flow >= 0 && speed >= 0){
            return flow / speed;
        }
        else{
            return -1;
        }
    }
    
}
