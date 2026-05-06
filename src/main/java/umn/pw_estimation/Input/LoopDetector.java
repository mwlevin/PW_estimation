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
public class LoopDetector extends Detector {
    
    private Coordinate loc;
    
    public LoopDetector(String name, Coordinate loc){
        super(name);
        this.loc = loc;
    }
    
    public Coordinate getLocation(){
        return loc;
    }
    
    public int getLast30sCount(){
        return 12;
    }
    
    public double getLast30sSpeed(){
        return 65;
    }
    
    public double getLast30sDensity(){
        return getLast30sFlow() / getLast30sSpeed();
    }
}
