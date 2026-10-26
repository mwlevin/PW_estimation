/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.PW;

/**
 *
 * @author cege
 */
public class TrafficRegime {
    public double speed, flow;
    
    public TrafficRegime(double speed, double flow){
        this.speed = speed;
        this.flow = flow;
    }
    public double getSSE(double q, double u){
        return (q - flow)*(q - flow) + (u - speed)*(u - speed);
    }

    // flow (veh/s) and speed (m/s) have different units and magnitudes, so scale each before comparing
    public double getSSE(double q, double u, double q_scale, double u_scale){
        double q_diff = (q - flow) / q_scale;
        double u_diff = (u - speed) / u_scale;
        return q_diff * q_diff + u_diff * u_diff;
    }
    
}