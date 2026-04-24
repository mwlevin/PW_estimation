/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.PW;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;

/**
 *
 * @author mlevin
 */
public class Corridor {
    
    private double tau = 1; // speed adaptation term
    private double chi = 0.0001; // avoid divide by 0 in traffic pressure
    
    private Cell[] cells;
    
    // each link should have a constant number of lanes
    private List<Link> links;
    
    private double dt;
    private double time;
    
    
    public Corridor(double dt){
        links = new ArrayList<>();
        this.dt = dt;
    }
    
    public Corridor(Link[] input, double dt){
        this(dt);
        
        
        for(Link l : input){
            links.add(l);
        }
    }
    
    private void constructCells(){
        
        // first, join links to connect cells at link boundaries
        
        for(int i = 0; i < links.size()-1; i++){
            links.get(i).addNextLink(links.get(i+1));
        }
        
        // now construct a total array of all cells
        int total_size = 0;
        
        for(Link l : links){
            total_size += l.cells.length;
        }
        
        
        cells = new Cell[total_size];
        
        int idx = 0;
        
        for(Link l : links){
            for(Cell c: l.cells){
                cells[idx] = c;
                
                cells[idx].setIndices(idx*2, idx*2 + 1);

                
                idx++;
            }
        }
    }
    
    private RealMatrix F, Ft;
    private RealMatrix Q;
    private RealMatrix P_t_tp, P_t_t;
    private RealVector x_t_tp, x_t_t;
    
    
    public void init(){
        constructCells();
        
        int size = cells.length*2;
        
        time = System.currentTimeMillis();
        
        initializeF(size);
        x_t_tp = new ArrayRealVector(size);
        x_t_t = new ArrayRealVector(size);
        
        for(Cell c : cells){
            x_t_t.setEntry(c.k_idx(), c.density);
            x_t_t.setEntry(c.v_idx(), c.speed);
        }
        
        P_t_t = new Array2DRowRealMatrix(size, size);
        P_t_tp = new Array2DRowRealMatrix(size, size);
        
        Q = new Array2DRowRealMatrix(size, size);
    }
    
    public void nextTimestep(){
        
        predict(); // this populates P_t_tp and x_t_tp
        update(); // this populates P_t_t and x_t_t
        saveValuesInCells(); // moves x_t_t values to cells
        
        time += dt * 1000;
    }
    
    private void predict(){
        
        
        // vector of (k, v) per cell
        x_t_tp.set(0);
        
        
        for(Cell c : cells){
            double C = calcC(c);
                    
            double dx1 = c.getLength();
            double dx2 = (c.getNext() != null? c.getNext().getLength() : c.getLength());
            
            double k_i_t = c.density;
            

            double v_i_t = c.speed;
            double v_ip_t = 0; // in refers to i-next
            double k_ip_t = 0;
            double q_ip_t = 0;
            
            if(c.getPrev() != null){
                k_ip_t = c.getPrev().density;
                v_ip_t = c.getPrev().speed;
                q_ip_t = k_ip_t * v_ip_t;
            }
            else{
                k_ip_t = c.density;
                v_ip_t = c.speed;
                // starting cell should have a detector
                q_ip_t = c.getDetector().getLast30sFlow();
            }
            
            
            double k_in_t = (c.getNext() != null? c.getNext().density : c.density); 
            
            double eq_speed = (c.getNext() != null? c.getNext().getLink().getEquilibriumSpeed(k_in_t) : c.getLink().getEquilibriumSpeed(k_in_t));
            
            double q_i_t = v_i_t * k_i_t;
            
            double k_i_tn = k_i_t + dt/3600.0 * (q_ip_t/dx1 - q_i_t/dx1); // density for cell i at t+1
            double v_i_tn = v_i_t + dt/3600.0 * (- (v_i_t * v_i_t - v_ip_t * v_ip_t) / (2 * dx1) + (eq_speed - v_i_t)/tau -
                    C / (k_i_t + chi) * (k_in_t - k_i_t)/dx2 );

            x_t_tp.setEntry(c.k_idx(), k_i_tn);
            x_t_tp.setEntry(c.v_idx(), v_i_tn);
        }
        
        
        // moving on to next time step, so t -> tp
        RealMatrix P_tp_tp = P_t_t;
        //P_t_tp = F.multiply(P_tp_tp).multiply(Ft).add(Q);
    }
    
    private double calcC(Cell cell){
        return Math.min(20, Math.max(10, cell.getLink().getFFSpeed() / tau));
    }
    
    private void initializeF(int size){
        F = new Array2DRowRealMatrix(size, size);
        
        for(Cell c : cells){
            
        }
        
        
        
        
        Ft = F.transpose();
    }
    
    private void update(){
        x_t_t = x_t_tp.copy();
        P_t_t = P_t_tp.copy();
    }
    
    private void saveValuesInCells(){
        for(Cell c : cells){
            c.density = x_t_t.getEntry(c.k_idx());
            c.speed = x_t_t.getEntry(c.v_idx());
        }
    }
    
    public void printCells(){
        System.out.println("t="+String.format("%.2f", time/1000.0)+" --");
        for(Cell c : cells){
            System.out.println(c.k_idx()/2+"\t"+c.density+"\t"+c.speed);
        }
        System.out.println("--");
    }
}
