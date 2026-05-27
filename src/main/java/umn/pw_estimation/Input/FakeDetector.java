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
public class FakeDetector extends Detector {
    
    private Coordinate loc;
    private double speed;
    private int count;
    
    public FakeDetector(String name, Coordinate loc, int count, double speed){
        super(name);
        this.loc = loc;
        this.count = count;
        this.speed = speed;
    }
    
    public Coordinate getLocation(){
        return loc;
    }
    
    public int getLast30sCount(long time){
        return count;
    }
    
    public double getLast30sSpeed(long time){
        return speed;
    }
    
}
