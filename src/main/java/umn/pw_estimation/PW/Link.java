/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.PW;

import umn.pw_estimation.Input.Detector;
import java.util.List;

/**
 *
 * @author mlevin
 */
public class Link {
    public Cell[] cells;
    
    
    private double v; // free flow speed m/s
    private double Q; // capacity per lane veh/s
    private double w; // congested wave speed m/s
    private double K; // jam density per lane veh/m
    private int numLanes;
    private double length; // units of meters
    private double cell_len;
    
    private String name;
    
    private List<Coordinate> coords;
    
    // dt in sec
    public Link(String name, double length, double dt, double v, double maxspeed, double Q, double w, double K, int numLanes){
        this.length = length * 1609.3;  // miles to meters
        this.v = v / 2.237;  // converting units here, mph to m/s
        this.Q = Q / 3600.0;  // veh/hr to veh/s
        this.w = w / 2.237;
        this.K = K / 1609.3;  // veh/mi to veh/m
        this.numLanes = numLanes;
        
        double dx = maxspeed / 2.237 * dt;  // converts mph to m/s
        
        int numcells = (int)Math.max(1, Math.floor(this.length/dx));
        
        cells = new Cell[numcells];
        
        cell_len = this.length / numcells;
        
        //System.out.println("check cell length "+cell_len);

        
        Cell prev = null;
        for(int i = 0; i < cells.length; i++){
            cells[i] = new Cell(cell_len, this, prev);
            prev = cells[i];
        }
     
        
    }
    
    public Link(String name, List<Coordinate> coords, double dt, double v, double maxspeed, double Q, double w, double K, int numLanes){
        this(name, calcLength(coords), dt, v, maxspeed, Q, w, K, numLanes);
        this.coords = coords;
    }
    
    public boolean addDetector(Detector det){

        Coordinate location = det.getLocation();
        
        // also need to determine whether detector is on link
        for(int i = 0; i < coords.size()-1; i++){
            // detector should be almost in a straight line on the road
            
            double dist1 = Coordinate.dist(coords.get(i), location);
            double dist2 = Coordinate.dist(location, coords.get(i+1));
            double total_dist = Coordinate.dist(coords.get(i), coords.get(i+1));
            
            
            // admit 200ft error for curvature, road coordinate differences, etc.
            // if dist1 + dist2 < total_dist means it is outside of the link
            if(Math.abs((dist1 + dist2 - total_dist)*5280) <= 200){
                // now need to compute distance along road
                addDetector(dist1 + calcLength(coords, i), det);
                return true;
            }
        }
        
        return false;
    }
    
    public void addDetector(double position, Detector det){
        assert(position > 0 && position < length);
        
        int cell_idx = (int)Math.min(cell_len-1, Math.ceil(position / cell_len));
        
        cells[cell_idx].setDetector(det);
    }
    
    public double getEquilibriumSpeed(double k){
        
        if(k == 0){
            return v;
        }
        double q = Math.min(v*k, Math.min(getQ(), -w*(k-getK())));
        return q/k;

    }
    
    public double getDerivEqSpeed(double k){
        
        if(v * k < getQ()){
            return 0;
        }
        else if(getQ() <= -w * (k - getK())){
            return -getQ()/(k*k);
        }
        else{
            return -w * getK() / (k*k);
        }
    }
    
    public double getW(){
        return w;
    }
    
    public double getQ_perLane(){
        return Q;
    }
    
    public double getK_perLane(){
        return K;
    }
    
    public double getQ(){
        return Q * numLanes;
    }
    
    public double getK(){
        return K * numLanes;
    }
    
    public int getNumLanes(){
        return numLanes;
    }
    
    public double getFFSpeed(){
        return v;
    }
    
    
    public static double calcLength(List<Coordinate> coords){
        return calcLength(coords, coords.size()-1);
    }
    
    public static double calcLength(List<Coordinate> coords, int stop_idx){
        double total = 0;
        
        for(int i = 0; i < stop_idx; i++){
            total += Coordinate.dist(coords.get(i), coords.get(i+1));
        }
        
        return total;
    }
    
    public void addNextLink(Link l){
        // connect the cells 
        l.cells[0].setPrev(cells[cells.length-1]);
        cells[cells.length-1].setNext(l.cells[0]);
    }
    
    public int size(){
        return cells.length;
    }
    
    public String toString(){
        return ""+name;
    }
}
