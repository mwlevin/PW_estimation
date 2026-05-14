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
    
    private String name;
    
    public Detector(String name){
        this.name = name;
    }
    
    public abstract Coordinate getLocation();
    
    
    public String getName(){
        return name;
    }
    
    public String toString(){
        return name;
    }
    
    public abstract int getLast30sCount(long t);
    
    public double getLast30sFlow(long t){
        return getLast30sCount(t) * 3600.0/30.0; // units conversion
    }
    public abstract double getLast30sSpeed(long t);
    
    public double getLast30sDensity(long t){
        return getLast30sFlow(t) / getLast30sSpeed(t);
    }
    
}
