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
        if (time >= 5*3){  // 5 time steps of 3 seconds each
            return 12;
        }
        else{
            return 0;
        }
    }
    
    public double getLast30sSpeed(long time){
        return 35 * 0.447;  // mph to m/s conversion
    }
    
}
