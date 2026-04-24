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
    
    protected double speed, density;
    
    private int k_idx;
    private int v_idx;
    
    private double length;
    
    
    private Cell prev, next;
    
    private Detector detector;
    
    // this refers to a detector on an entrance or exit that observes vehicles entering or exiting
    private Detector inflow, outflow;
    
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
    }
    
    public boolean hasDetector(){
        return detector != null;
    }
    
    public void setDetector(Detector det){
        detector = det;
    }
    
    
    
    
    public void addInflow(Detector det){
        this.inflow = det;
    }
    
    public boolean hasInflow(){
        return inflow != null;
    }
    
    public Detector getInflow(){
        return inflow;
    }
    
    public void addOutflow(Detector det){
        this.outflow= det;
    }
    
    public boolean hasOutflow(){
        return outflow != null;
    }
    
    public Detector getOutflow(){
        return outflow;
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
    
    public void setIndices(int k_idx, int v_idx){
        this.k_idx = k_idx;
        this.v_idx = v_idx;
    }
    
    protected double getFlow(){
        return speed * density;
    }
}
