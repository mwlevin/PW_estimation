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
    
    public FakeDetector(String name, Coordinate loc, int par, int par1){
        super(name, Type.Mainline);
        this.loc = loc;
    }
    
    public Coordinate getLocation(){
        return loc;
    }
    
    public double getLast30sCount(long time){
        return 12;
    }
    
    public double getLast30sSpeed(long time){
        return 35 * 0.447;  // mph to m/s conversion
    }
    
}
