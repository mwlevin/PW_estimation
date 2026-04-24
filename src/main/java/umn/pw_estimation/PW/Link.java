/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.PW;

import java.util.List;

/**
 *
 * @author mlevin
 */
public class Link {
    protected Cell[] cells;
    
    
    private double v; // free flow speed miles/hr
    private double Q; // capacity per lane veh/hr
    private double w; // congested wave speed miles/hr
    private double K; // jam density per lane veh/mile
    private int numLanes;
    private double length; // units of miles
    private double cell_len;
    
    private List<Coordinate> coords;
    
    // dt in sec
    public Link(double length, double dt, double v, double Q, double w, double K){
        this.length = length;
        this.v = v;
        this.Q = Q;
        this.w = w;
        this.K = K;
        
        double dx = v * dt / 3600.0;
        
        int numcells = (int)Math.max(1, Math.ceil(length/dx));
        
        cells = new Cell[numcells];
        
        cell_len = length / numcells;
        
        Cell prev = null;
        for(int i = 0; i < cells.length; i++){
            cells[i] = new Cell(cell_len, this, prev);
            prev = cells[i];
        }
     
        
    }
    
    public void addDetector(double position, Detector det){
        assert(position > 0 && position < length);
        
        int cell_idx = (int)Math.ceil(position / cell_len);
        
        cells[cell_idx].setDetector(det);
    }
    
    public double getEquilibriumSpeed(double k){
        
        return  Math.min(k, Math.min(getQ() / k,  -w + w * getK()/k));
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
    
    public Link(List<Coordinate> coords, double dt, double v, double Q, double w, double K){
        this(calcLength(coords), dt, v, Q, w, K);
        this.coords = coords;
    }
    
    public static double calcLength(List<Coordinate> coords){
        double total = 0;
        
        for(int i = 0; i < coords.size()-1; i++){
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
}
