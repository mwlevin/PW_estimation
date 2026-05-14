/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.PW;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Pattern;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.MatrixUtils;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import umn.pw_estimation.Input.Detector;
import umn.pw_estimation.Input.DetectorGroup;
import umn.pw_estimation.Input.HistoricalDetector;

/**
 *
 * @author mlevin
 */
public class Corridor {
    
    private double tau = 1; // speed adaptation term
    private double chi = 0.01; // avoid divide by 0 in traffic pressure // originally was 0.0001
    
    private Cell[] cells;
    
    // each link should have a constant number of lanes
    private List<Link> links;
    
    private double dt;
    private long time;
    
    
    public Corridor(double dt){
        links = new ArrayList<>();
        this.dt = dt;
    }
    
    public Corridor(List<Link> links, double dt){
        this.links = links;
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
        
        int var_idx = 0;
        int cell_idx = 0;
        
        for(Link l : links){
            for(Cell c: l.cells){
                cells[cell_idx++] = c;
                c.set_k_idx(var_idx++);
                c.set_v_idx(var_idx++);
                
                if(c.hasInflowDet()){
                    c.set_inflow_idx(var_idx++);
                }
                if(c.hasOutflowDet()){
                    c.set_outflow_idx(var_idx++);
                }
            }
        }
    }
    
    private RealMatrix Q, F_t, I;
    private RealMatrix P_t_tp, P_t_t, R_t;
    private RealVector x_t_tp, x_t_t;
    private RealVector y_t;
    
    public void init(long time){
        constructCells();
        
        int size = 0;

        for(Cell c : cells){
            size += c.getNumVariables();
        }
        
        
        
        

        x_t_tp = new ArrayRealVector(size);
        x_t_t = new ArrayRealVector(size);
        
        y_t = new ArrayRealVector(size);
        
        for(Cell c : cells){
            if(c.hasDetector()){
                c.density = c.getDetector().getLast30sDensity(time);
                c.speed = c.getDetector().getLast30sSpeed(time);
            }
            
            x_t_t.setEntry(c.k_idx(), c.density);
            x_t_t.setEntry(c.v_idx(), c.speed);
            
            if(c.hasInflowDet()){
                x_t_t.setEntry(c.inflow_idx(), c.getInflow(time));
            }
            
            if(c.hasOutflowDet()){
                x_t_t.setEntry(c.outflow_idx(), c.getOutflow(time));
            }
        }
        
        F_t = new Array2DRowRealMatrix(size, size);
        P_t_t = new Array2DRowRealMatrix(size, size);
        R_t = new Array2DRowRealMatrix(size, size);
        P_t_tp = new Array2DRowRealMatrix(size, size);
        
        I = MatrixUtils.createRealIdentityMatrix(size);
        
        
        calcR();
        P_t_t = R_t;
        
        Q = new Array2DRowRealMatrix(size, size);
        
        
    }
    
    public void nextTimestep(){
        
        predict(); // this populates P_t_tp and x_t_tp
        update(); // this populates P_t_t and x_t_t
        
        // this makes it easier to work with values later on
        // do I need to multithread running the kalman filter and the cell data processing?
        // if so, I will copy values from the cells into an array and process that array. Copying should be fast enough to not cause a synchronization error.
        saveValuesInCells(); // moves x_t_t values to cells
        
        time += dt * 1000;
    }
    
    private void predict(){
        
        
        // vector of (k, v) per cell
        x_t_tp.set(0);
        
        
        int cell_idx = 0;
        
        for(Cell c : cells){
            cell_idx ++;
            
            double C = calcC(c);
                    
            double dx1 = c.getLength();
            double dx2 = (c.getNext() != null? c.getNext().getLength() : c.getLength());
            
            double k_i_t = c.density;
            
            double inflow = 0;
            double outflow = 0;
            
            if(c.hasInflowDet()){
                inflow = c.inflow;
            }
            if(c.hasOutflowDet()){
                outflow = c.outflow;
            }

            
            // assume added flow enters cell, so it increases occupancy (increases density)
            // assume counts on inflow/outflow are correct, and only counts are used, so 0 noise
            k_i_t += (inflow - outflow) / c.getLength();
            

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
                q_ip_t = q_ip_t = k_ip_t * v_ip_t;
            }
            
            
            double k_in_t = (c.getNext() != null? c.getNext().density : c.density); 
            
            double eq_speed = (c.getNext() != null? c.getNext().getLink().getEquilibriumSpeed(k_in_t) : c.getLink().getEquilibriumSpeed(k_in_t));
            
            double q_i_t = v_i_t * k_i_t;
            
            double k_i_tn = k_i_t + dt/3600.0 * (q_ip_t/dx1 - q_i_t/dx1); // density for cell i at t+1
            /*
            double v_i_tn = v_i_t + dt/3600.0 * ( (-v_i_t * v_i_t + v_ip_t * v_ip_t) / (2 * dx1) + (eq_speed - v_i_t)/tau -
                    C / (k_i_t + chi) * (k_in_t - k_i_t)/dx2 );
            */
            
            double v_i_tn = v_i_t + dt/3600.0 * ( (-v_i_t * v_i_t + v_ip_t * v_ip_t) / (2 * dx1));

            //System.out.println("check "+cell_idx+" "+v_i_tn+" "+v_i_t+" "+v_ip_t);
            //System.out.println("\t"+(eq_speed - v_i_t)/tau);
            //System.out.println("\t"+C / (k_i_t + chi) * (k_in_t - k_i_t)/dx2 );
            //System.out.println("check "+cell_idx+" "+k_i_tn+" "+q_ip_t+" "+q_i_t+" "+k_i_t);
            
            x_t_tp.setEntry(c.k_idx(), k_i_tn);
            x_t_tp.setEntry(c.v_idx(), v_i_tn);
            
            if(c.hasInflowDet()){
                x_t_tp.setEntry(c.inflow_idx(), inflow);
            }
            if(c.hasOutflowDet()){
                x_t_tp.setEntry(c.outflow_idx(), outflow);
            }
            
            // now need to calculate F, which is the Jacobian matrix of df/dx where f is the PW state update equation
            
            if(c.hasInflowDet()){
                F_t.setEntry(c.inflow_idx(), c.inflow_idx(), 1);
                F_t.setEntry(c.k_idx(), c.inflow_idx(), 1.0 / c.getLength());
            }
            
            if(c.hasOutflowDet()){
                F_t.setEntry(c.outflow_idx(), c.outflow_idx(), 1);
                F_t.setEntry(c.k_idx(), c.outflow_idx(), -1.0 / c.getLength());
            }
            
            if(c.getPrev() != null){
                F_t.setEntry(c.k_idx(), c.k_idx(), 1 - dt / 3600.0/dx1 * c.speed);
            
                F_t.setEntry(c.k_idx(), c.v_idx(), -dt / 3600.0 /dx1 * c.density);
            
                F_t.setEntry(c.k_idx(), c.getPrev().k_idx(), dt / 3600.0 /dx1 * c.getPrev().speed);
                
                F_t.setEntry(c.k_idx(), c.getPrev().v_idx(), dt / 3600.0 /dx1 * c.getPrev().density);
                
              
                
                F_t.setEntry(c.v_idx(), c.getPrev().v_idx(), dt / 3600.0  * v_ip_t / dx2);
                        
                F_t.setEntry(c.v_idx(), c.v_idx(), 1 - dt  / 3600.0 * v_i_t / dx2  - 1/tau);
               
                
            }
            else{
                F_t.setEntry(c.k_idx(), c.k_idx(), 1);
            
                F_t.setEntry(c.k_idx(), c.v_idx(), 0);
                
                F_t.setEntry(c.v_idx(), c.v_idx(), 1 - 1/tau);
            }
            
            
            
            if(c.getNext() != null){
                F_t.setEntry(c.v_idx(), c.k_idx(), dt / 3600.0 /dx2  * C / (k_i_t + chi) - 
                        dt / 3600.0 /dx2  * (k_in_t - k_i_t)* C / ((k_i_t + chi) * (k_i_t + chi))  );
                                
                F_t.setEntry(c.v_idx(), c.getNext().k_idx(), dt/3600/dx2 * (c.getLink().getDerivEqSpeed(k_in_t)/tau - C / (k_i_t + chi)));
  
            }
            else{
                F_t.setEntry(c.v_idx(), c.k_idx(), dt / 3600.0  * c.getLink().getDerivEqSpeed(k_in_t)/tau);
            }
        }
        
        
        calcQ();
        // moving on to next time step, so t -> tp
        P_t_tp = F_t.multiply(P_t_t).multiply(F_t.transpose()).add(Q);
        
        
    }
    
    private double calcC(Cell cell){
        return Math.min(20, Math.max(10, cell.getLink().getFFSpeed() / tau));
    }
    
 
    
    private void update(){
        // obtain detector data
        for(Cell c : cells){
            if(c.hasDetector()){
                // this is the measurement residual
                double residual_k = c.getDetector().getLast30sDensity(time) - x_t_tp.getEntry(c.k_idx());
                double residual_v = c.getDetector().getLast30sSpeed(time) - x_t_tp.getEntry(c.v_idx());
                
                y_t.setEntry(c.k_idx(), residual_k);
                y_t.setEntry(c.v_idx(), residual_v);
                
                if(c.hasInflowDet()){
                    double residual_inflow = c.getInflow(time) - x_t_tp.getEntry(c.inflow_idx());
                    y_t.setEntry(c.inflow_idx(), residual_inflow);
                }
                if(c.hasOutflowDet()){
                    double residual_outflow = c.getOutflow(time) - x_t_tp.getEntry(c.outflow_idx());
                    y_t.setEntry(c.outflow_idx(), residual_outflow);
                }
                
                //System.out.println("residual "+residual_k+" "+residual_v+" "+c.getDetector().getLast30sDensity());
            }
        }
        
        // H is the identity matrix
        calcR();
        
        RealMatrix S_t = P_t_tp.add(R_t);
        
        //double determinant = new LUDecomposition(S_t).getDeterminant();
        
        //System.out.println("S_t="+S_t);
        //System.out.println("det="+determinant);
        
        // Kalman gain
        RealMatrix K_t = P_t_tp.multiply(MatrixUtils.inverse(S_t));
        
        
        x_t_t = x_t_tp.add(K_t.operate(y_t));
        
        P_t_t = I.subtract(K_t).multiply(P_t_tp);
        
        
        
    }
    
    private void saveValuesInCells(){
        for(Cell c : cells){
            c.density = x_t_t.getEntry(c.k_idx());
            c.speed = x_t_t.getEntry(c.v_idx());
            
            if(c.hasInflowDet()){
                c.inflow = x_t_t.getEntry(c.inflow_idx());
            }
            if(c.hasOutflowDet()){
                c.outflow = x_t_t.getEntry(c.outflow_idx());
            }
        }
    }
    
    public void printCells(){
        System.out.println("t="+String.format("%.2f", time/1000.0)+" --");
        for(Cell c : cells){
            System.out.println(c.k_idx()/2+"\t"+c.density+"\t"+c.speed);
        }
        System.out.println("--");
    }
    
    public void calcR(){
        
        // for now, assuming 0 measurement error. Calculating variance based on speed estimation from average vehicle length.
        // Speed = sum_i E[L_i]/t_i and L_i is random -> but using average speed results in approximate normal distribution
        // assume normal distribution with mean 14.7 and stdev 1.5 for car lengths
        // variance in speed should be variance in car length / n
        // k = q/v
        // cov(q/v, v) = q * cov(1/v, v) = 1 - E[v]E[1/v]
        // E[1/v] is approximately 1/mu + sigma^2/mu^3
        // variance(1/v) is approximately sigma^2/mu^4
        
        double len_mean = 14.7;
        
        double len_stdev = 1.5; // stdev in vehicle length
        double len_var = len_stdev * len_stdev;
        
        double E_inv_len = 1/len_mean + len_stdev * len_stdev / (len_mean * len_mean * len_mean); // approximation
        
        double variance_inv_len = len_stdev * len_stdev / (len_mean * len_mean * len_mean * len_mean);
        
        for(Cell c : cells){
            if(c.hasDetector()){
                
                double q = c.getDetector().getLast30sFlow(time);
                
                // inflow/outflow is handled by a different detector, so assume 0 covariance
                // also assume 0 variance (perfect information)
                if(c.hasInflowDet()){
                    R_t.setEntry(c.inflow_idx(), c.inflow_idx(), 0);
                }
                if(c.hasOutflowDet()){
                    R_t.setEntry(c.outflow_idx(), c.outflow_idx(), 0);
                }
                
                R_t.setEntry(c.v_idx(), c.v_idx(), len_var * len_var / c.getDetector().getLast30sCount(time));
                
                double cov = q * (1 - len_mean * E_inv_len); 
                R_t.setEntry(c.v_idx(), c.k_idx(), cov);
                R_t.setEntry(c.k_idx(), c.v_idx(), cov);
                
                R_t.setEntry(c.k_idx(), c.k_idx(), q * variance_inv_len);
            }
        }
    }
    
    public void calcQ(){
        
        for(Cell c : cells){
            
            // covariance is likely negative: in congestion, larger k => smaller v
            // this is variance due to FD not fully describing traffic evolution
            // I don't know which values these should have
            double scale = 1;
            double var_k = 5 * scale;
            double var_v = 1 * scale;
            double cov = - var_k * var_v;
            
            double cov_inflow = 5 * scale;
            
            Q.setEntry(c.k_idx(), c.k_idx(), var_k);
            Q.setEntry(c.v_idx(), c.v_idx(), var_v);
            Q.setEntry(c.k_idx(), c.v_idx(), cov);
            Q.setEntry(c.v_idx(), c.k_idx(), cov);
            
            // inflow/outflow is a separate detector and behavior so assume 0 covariance
            if(c.hasInflowDet()){
                Q.setEntry(c.inflow_idx(), c.inflow_idx(), scale);
            }
            
            if(c.hasOutflowDet()){
                Q.setEntry(c.outflow_idx(), c.outflow_idx(), scale);
            }
        }
    }
    
    public boolean addDetector(Detector det){
        for(Link l : links){
            if(l.addDetector(det)){
                return true;
            }
        }
        
        return false;
    }
}
