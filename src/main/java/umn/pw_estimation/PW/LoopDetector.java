/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.PW;

/**
 *
 * @author mlevin
 */
public class LoopDetector extends Detector {
    
    public LoopDetector(String name){
        super(name);
    }
    
    public int getLast30sCount(){
        return 12;
    }
    
    public double getLast30sSpeed(){
        return 55;
    }
    
    public double getLast30sDensity(){
        return getLast30sFlow() / getLast30sSpeed();
    }
}
