/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.PW;

import umn.pw_estimation.Input.Detector;

/**
 *
 * @author mlevin
 */
public class Cell {
    
    protected double speed, density, inflow, outflow;
    
    private int k_idx;
    private int v_idx;
    public int cell_idx;
    private int inflow_idx, outflow_idx;
    
    private double length;
    
    
    private Cell prev, next;
    
    private Detector detector;
    
    // this refers to a detector on an entrance or exit that observes vehicles entering or exiting
    private Detector inflow_det, outflow_det;
    
    private Link link;
    
    public Cell(double length, Link link, Cell prev){
        this(length, link, prev, null);
    }
    
    public Cell(double length, Link link, Cell prev, Detector det){
        this.prev = prev;
        
        if(prev != null){
            prev.next = this;
        }
        
        this.length = length;
        this.link = link;
        
        this.density = 0;
        this.speed = link.getFFSpeed();
        
        k_idx = -1;
        v_idx = -1;
        inflow_idx = -1;
        outflow_idx = -1;
    }
    
    public int getNumVariables(){
        int count = 2;
        
        if(inflow_det != null){
            count++;
        }
        if(outflow_det != null){
            count++;
        }
        
        return count;
    }
    
    public boolean hasDetector(){
        return detector != null;
    }
    
    public void setDetector(Detector det){
        detector = det;
    }
    
    
    
    
    public void addInflowDet(Detector det){
        this.inflow_det = det;
    }
    
    public boolean hasInflowDet(){
        return inflow_det != null;
    }
    
    public Detector getInflowDet(){
        return inflow_det;
    }
    
    public void addOutflowDet(Detector det){
        this.outflow_det = det;
    }
    
    public boolean hasOutflowDet(){
        return outflow_det != null;
    }
    
    public Detector getOutflowDet(){
        return outflow_det;
    }
    
    
    
    public double getLength(){
        return length;
    }
    
    public Detector getDetector(){
        return detector;
    }
    
    public Link getLink(){
        return link;
    }
    public Cell getNext(){
        return next;
    }
    
    public Cell getPrev(){
        return prev;
    }
    
    protected void setNext(Cell n){
        next = n;
    }
    
    protected void setPrev(Cell p){
        prev = p;
    }
    
    public int k_idx(){
        return k_idx;
    }
    
    public int v_idx(){
        return v_idx;
    }
    
    public int inflow_idx(){
        return inflow_idx;
    }
    
    public int outflow_idx(){
        return outflow_idx;
    }
    
    public double getInflow(long t){
        if(inflow_det != null){
            return inflow_det.getLast30sCount(t)/30;
        }
        
        return 0;
    }
    
    public double getOutflow(long t){
        if(outflow_det != null){
            return outflow_det.getLast30sCount(t)/30;
        }
        return 0;
    }
    
    public void set_k_idx(int idx){
        k_idx = idx;
    }
    public void set_v_idx(int idx){
        v_idx = idx;
    }
    public void set_inflow_idx(int idx){
        inflow_idx = idx;
    }
    public void set_outflow_idx(int idx){
        outflow_idx = idx;
    }
    
    
    protected double getFlow(){
        return speed * density;
    }
    
    protected double getSpeed(){
        return speed;
    }
    
    public int getTrafficRegime(){
        /*double Q = link.getQ();
        double K = link.getK();
        double w = link.getW();*/
        
        /*double sd1 = (v_idx() - speed) * (v_idx() - speed);
        double sd2 = (v_idx() - Q/(K-(Q/w))) * (v_idx() - Q/(K-(Q/w)));
        double sd3 = (v_idx() - (0.8/1.1)*(Q/(K-(Q/w)))) * (v_idx() - (0.8/1.1)*(Q/(K-(Q/w))));
        double sd4 = (v_idx() - (0.45/1.1)*(Q/(K-(Q/w)))) * (v_idx() - (0.45/1.1)*(Q/(K-(Q/w))));
        double sd5 = (v_idx() - 0) * (v_idx() - 0);
        
        double fd1 = (getFlow() - 0) * (getFlow() - 0);
        double fd2 = (getFlow() - (0.9/2.1)*Q) * (getFlow() - (0.9/2.1)*Q);
        double fd3 = (getFlow() - (1.9/2.1)*Q) * (getFlow() - (1.9/2.1)*Q);
        double fd4 = (getFlow() - Q) * (getFlow() - Q);
        double fd5 = (getFlow() - (0.85/1.45)*Q) * (getFlow() - (0.85/1.45)*Q);
        double fd6 = (getFlow() - (0.25/1.45)*Q) * (getFlow() - (0.25/1.45)*Q);*/
        
        /*double sse1 = sd1 + fd1;
        double sse2 = sd1 + fd2;
        double sse3 = sd1 + fd3;
        double sse4 = sd1 + fd4;
        double sse5 = sd2 + fd4;
        double sse6 = sd3 + fd5;
        double sse7 = sd4 + fd6;
        double sse8 = sd5 + fd1;
        
        double min = Math.min(sse1, Math.min(sse2, Math.min(sse3, Math.min(sse4, Math.min(sse5, Math.min(sse6, Math.min(sse7, sse8)))))));
        */
        /*if(min == sse1){
        return 0;
        }
        if(min == sse2){
        return 1;
        }
        if(min == sse3){
        return 2;
        }
        if(min == sse4){
        return 3;
        }
        if(min == sse5){
        return 4;
        }
        if(min == sse6){
        return 5;
        }
        if(min == sse7){
        return 6;
        }
        if(min == sse8){
        return 7;
        }
        return -1;*/
        
        double best_sse = Double.MAX_VALUE;
        int best = -1;
        
        TrafficRegime[] array = link.array();
        
        
        for(int i = 1; i <= array.length - 1; i++){
            double sse = array[i].getSSE(getFlow(), getSpeed());
            if(sse < best_sse){
            best_sse = sse;
            best = i;
            }
        }
        return best;
    }
}
