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
            return inflow_det.getLast30sCount(t);
        }
        
        return 0;
    }
    
    public double getOutflow(long t){
        if(outflow_det != null){
            return outflow_det.getLast30sCount(t);
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
}
