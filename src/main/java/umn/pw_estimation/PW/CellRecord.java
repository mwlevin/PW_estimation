/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.PW;

/**
 *
 * @author michael
 */
public class CellRecord {
    public long time;
    public double flow, density, speed;
    public int regime;
    
    public CellRecord(long time, double flow, double density, double speed, int regime){
        this.time = time;
        this.flow = flow * 3600; // veh/s to veh/hr, consistent with density and speed below
        this.density = density * 1609.34;
        this.speed = speed * 2.23694;
        this.regime = regime;
    }
}
