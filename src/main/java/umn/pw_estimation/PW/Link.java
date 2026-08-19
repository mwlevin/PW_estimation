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
    private double maxspeed; // exceeding this violates CFL
    
    private String name;
    
    private List<Coordinate> coords;
    
    // dt in sec
    public Link(String name, double length, double dt, double v, double maxspeed, double Q, double w, double K, int numLanes){
        this.name = name;
        this.length = length * 1609.3;  // miles to meters
        this.v = v / 2.237;  // converting units here, mph to m/s
        this.Q = Q / 3600.0;  // veh/hr to veh/s
        this.w = w / 2.237;
        this.K = K / 1609.3;  // veh/mi to veh/m  // K = jam density
        this.numLanes = numLanes;
        this.maxspeed = maxspeed / 2.237;
        
        double dx = maxspeed / 2.237 * dt;  // converts mph to m/s
        
        int numcells = (int)Math.max(1, Math.floor(this.length/dx));
        
        cells = new Cell[numcells];
        
        cell_len = Math.max(this.length / numcells, dx);

        Cell prev = null;
        for(int i = 0; i < cells.length; i++){
            cells[i] = new Cell(cell_len, this, prev);
            prev = cells[i];
        }
        
        regimeArray();
    }
    
    public Link(String name, List<Coordinate> coords, double dt, double v, double maxspeed, double Q, double w, double K, int numLanes){
        this(name, calcLength(coords), dt, v, maxspeed, Q, w, K, numLanes);
        this.coords = coords;
    }
    
    public double getMaxSpeed(){
        return maxspeed;
    }
    
    public boolean addDetector(Detector det){

        Coordinate location = det.getLocation();
        
        // also need to determine whether detector is on link
        for(int i = 0; i < coords.size()-1; i++){
            // detector should be almost in a straight line on the road
            
            double dist1 = Coordinate.dist(coords.get(i), location);
            double dist2 = Coordinate.dist(location, coords.get(i+1));
            double total_dist = Coordinate.dist(coords.get(i), coords.get(i+1));
            
            //System.out.println("\ttrying to add "+det+" "+name+" "+dist1+" "+dist2+" "+total_dist+" "+(5280*(dist1+dist2-total_dist)));
            
            // admit some error for curvature, road coordinate differences, etc.
            // if dist1 + dist2 < total_dist means it is outside of the link
            if(Math.abs((dist1 + dist2 - total_dist)*5280) <= 50){
                // now need to compute distance along road
                // distance is in miles so convert to meters
                
                
                addDetector((dist1 + calcLength(coords, i))*1609.3, det);
                return true;
            }
        }
        
        return false;
    }
    
    public void addDetector(double position, Detector det){
        assert(position > 0 && position < length);
        
        int cell_idx = (int)Math.min(cells.length-1, Math.ceil(position / cell_len));
        
        if(det.getType() == Detector.Type.Exit){
            cells[cell_idx].addOutflowDet(det);
        }
        else if(det.getType() == Detector.Type.Merge){
            cells[cell_idx].addInflowDet(det);
        }
        else{
            cells[cell_idx].setDetector(det);
        }
    }
    
    public double getEquilibriumSpeed(double k){
                
                /*System.out.println("\t   v="+v);
                System.out.println("\t   k="+k);
                //System.out.println("\t   v*k="+(v*k));
                System.out.println("\t   w="+w); */
                System.out.println("\t   K per lane ="+getK_perLane());
                System.out.println("\t   Q per lane ="+getQ_perLane());
                /*System.out.println("\t   -w*(k-getK)="+(-w*(k-getK())));
                System.out.println("\t   min 1="+Math.min(getQ(), -w*(k-getK())));
                System.out.println("\t   min 2="+(Math.min(v*k, Math.min(getQ(), -w*(k-getK())))));
                System.out.println("\t ......................");*/
        
        if(k == 0){
            return v;
        }
        double q = Math.min(v*k, Math.min(getQ_perLane(), -w*(k-getK_perLane())));  // previously no _perLane on Q or K
        
        if(k > 0.07){
            //System.out.println("\t   q="+q);
            //System.out.println("\t   k="+k);
        }
                //System.out.println("\t   q="+q);
                //System.out.println("\t   k="+k);
                //System.out.println("\t   q/k="+(q/k));
                //System.out.println("\t ......................");
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
    
    TrafficRegime[] array = new TrafficRegime[9];
    
    public void regimeArray(){
        array[1] = new TrafficRegime(v, 0);
        array[2] = new TrafficRegime(v, (0.9/2.1)*Q);
        array[3] = new TrafficRegime(v, (1.9/2.1)*Q);
        array[4] = new TrafficRegime(v, Q);
        array[5] = new TrafficRegime(Q/(K-(Q/w)), Q);
        array[6] = new TrafficRegime((0.8/1.1)*(Q/(K-(Q/w))), (0.85/1.45)*Q);
        array[7] = new TrafficRegime((0.45/1.1)*(Q/(K-(Q/w))), (0.25/1.45)*Q);
        array[8] = new TrafficRegime(0, 0);
    }
    public TrafficRegime[] array(){
        return array;
    }
}