/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.PW;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
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
    
    private double tau = 18; // speed adaptation term, in seconds

    public static final double C_0 = 40.25; // traffic pressure parameter c_0, m/s
    private double chi = 0.12; // avoid divide by 0 in traffic pressure // originally was 0.0001
    
    private Cell[] cells;
    
    // each link should have a constant number of lanes
    private List<Link> links;
    
    private double dt;
    private long time; // units of seconds
    
    private String name;
    
    
    public Corridor(String name, double dt){
        this(name, new ArrayList<Link>(), dt);
    }
    
    public Corridor(String name, List<Link> links, double dt){
        this.name = name;
        this.links = links;
        this.dt = dt;
    }
    
    public Corridor(String name, Link[] input, double dt){
        this(name, dt);
        
        
        for(Link l : input){
            links.add(l);
        }
    }
    
    public List<Link> getLinks(){
        return links;
    }
    
    public String getName(){
        return name;
    }
    
    public String toString(){
        return name;
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
                cells[cell_idx] = c;
                c.cell_idx = cell_idx;
                c.set_k_idx(var_idx++);
                c.set_v_idx(var_idx++);
                
                
                if(c.hasInflowDet()){
                    c.set_inflow_idx(var_idx++);
                }
                if(c.hasOutflowDet()){
                    c.set_outflow_idx(var_idx++);
                }
                
                cell_idx++;
            }
        }
        
        
    }
    
    private String[] indices;
    
    private RealMatrix Q, F_t, I;
    private RealMatrix P_t_tp, P_t_t, R_t;
    private RealVector x_t_tp, x_t_t;
    private RealVector y_t;
    
    public void init(long time){
        
        sortLinks();
        
        this.time = time;
        
        constructCells();
        
        int size = 0;

        for(Cell c : cells){
            size += c.getNumVariables();
        }
        
        indices = new String[size];
        
        for(Cell c : cells){
            indices[c.k_idx()] = c.cell_idx+" k";
            indices[c.v_idx()] = c.cell_idx+" v";
            
            if(c.hasInflowDet()){
                indices[c.inflow_idx()] = c.cell_idx+" inflow";
            }
            
            if(c.hasOutflowDet()){
                indices[c.outflow_idx()] = c.cell_idx+" outflow";
            }
        }

        x_t_tp = new ArrayRealVector(size);
        x_t_t = new ArrayRealVector(size);
        
        y_t = new ArrayRealVector(size);
        
        for(Cell c : cells){
            if(c.hasDetector() && c.getDetector().getLast30sDensity(time) >= 0 && c.getDetector().getLast30sSpeed(time) >= 0){  // previously only hasDetector
                // values of -1 indicates bad data or missing data
                double k_observed = Math.min(Math.max(c.getDetector().getLast30sDensity(time), 0), c.getLink().getK());
                double v_observed = Math.min(Math.max(c.getDetector().getLast30sSpeed(time), 0), c.getLink().getMaxSpeed());
                
                if(k_observed >= 0 && v_observed >= 0){  // change to strictly inequality?
                    c.density = k_observed;
                    c.speed = v_observed;  // already in m/s; no need for unit conversions
                }
                else{
                    c.density = 0;
                    c.speed = c.getLink().getFFSpeed();
                }
                //System.out.println("\t   ff speed= "+c.getLink().getFFSpeed());
            }

            x_t_t.setEntry(c.k_idx(), c.density);
            x_t_t.setEntry(c.v_idx(), c.speed);
            
            if(c.hasInflowDet()){
                double inflow = c.getInflow(time);
                x_t_t.setEntry(c.inflow_idx(), inflow);
                c.inflow = inflow;
            }
            
            if(c.hasOutflowDet()){
                double outflow = c.getOutflow(time);
                x_t_t.setEntry(c.outflow_idx(), outflow);
                c.outflow = outflow;
            }
        }
        
        F_t = new Array2DRowRealMatrix(size, size);
        P_t_t = new Array2DRowRealMatrix(size, size);
        R_t = new Array2DRowRealMatrix(size, size);
        P_t_tp = new Array2DRowRealMatrix(size, size);
        
        I = MatrixUtils.createRealIdentityMatrix(size);
        
        
        calcR();
        // copy so that later changes to R_t in calcR() do not also change P_t_t
        P_t_t = R_t.copy();

        Q = new Array2DRowRealMatrix(size, size);
        
        
    }
    
    public CellRecord[][] records;
    private long startTime;
    private int time_idx;
    
    public void recordCellData(long time){
        for(int i = 0; i < cells.length; i++){
            records[i][time_idx] = cells[i].createCellRecord(time);
        }
        time_idx ++;
    }
    
    public CellRecord[] getData(long time){
        CellRecord[] output = new CellRecord[records.length];
        
        int time_idx = (int)Math.round( (double)(time - this.startTime)/dt);
        
        for(int i = 0; i < output.length; i++){
            output[i] = records[i][time_idx];
        }
        return output;
    }
    
    public void printCrashData(File file, long crash_time, Coordinate location, int upstream_cells, int timestep, int num_timesteps) throws IOException{
        
        // what if crash_time isn't aligned with a time step
        crash_time -= crash_time % dt;
        
        Cell cell = findCell(location);
        int cell_idx = cell.cell_idx;
        
        int start_cell = cell_idx - upstream_cells;
        
        
        
        long start_time = crash_time - timestep * num_timesteps;
        
        PrintStream fileout = new PrintStream(new FileOutputStream(file), true);
        String header1 = "time";
        String header2 = "";
        

        for(int i = 0; i < upstream_cells+1; i++){
            header1 += ",cell "+i+",,";
            header2 += ",density,speed,regime";
        }
        
        fileout.println(header1);
        fileout.println(header2);
        
        
        for(long time = start_time; time <= crash_time; time += timestep){
            int time_idx = (int)Math.round( (double)(time - this.startTime)/dt);
            
            String line = ""+time;
            
            for(int i = 0; i < upstream_cells+1; i++){
                CellRecord c = records[start_cell+i][time_idx];
                line += ","+c.density+","+c.speed+","+c.regime;
            }
            
            fileout.println(line);
        }
            
        fileout.close();
    }
    
    public Cell findCell(Coordinate location){
        for(Link l : links){
            Cell output = l.findCell(location);
            
            if(output != null){
                return output;
            }
        }
        
        return null;
    }
    
    public void estimate(int endTime, PrintStream fileout) throws IOException{
        
        startTime = time;
        time_idx = 0;
        
        records = new CellRecord[cells.length][ (int)Math.ceil( ( (double)endTime - startTime)/dt)];
        
        if(fileout != null){
            printHeader(fileout);
        }
        
        while(time < endTime){
            if(fileout != null){
                printCellData(fileout);
            }
            
            recordCellData(time);
            
            nextTimestep();
        }

        if(fileout != null){
            fileout.close();
        }
    }
    
    private void printHeader(PrintStream out){
        // header
        String header1 = "";
        String header2 = "";
        header1 += "time";
        
        
        for(Cell c : cells){
            header1 += ", cell "+c.cell_idx+",,,";
            header2 += ", k, u, q, regime";
            
            if(c.hasDetector()){
                header1 += ", detector "+c.getDetector().getName()+",,";
                header2 += ", k, u, q";
            }
            
            if(c.hasInflowDet()){
                header1 += ", inflow "+c.getInflowDet().getName();
                header2 += ", q";
            }
            if(c.hasOutflowDet()){
                header1 += ", outflow "+c.getOutflowDet().getName();
                header2 += ", q";
            }
        }
        
        out.println(header1);
        out.println(header2);
    }
    
    private void printCellData(PrintStream out){
        String line = "";
        
        line += time;
        
        for(Cell c : cells){
            line += ", "+(c.density * 1609.34)+", "+(c.speed * 2.237)+", "+((c.density * 1609.34) * (c.speed * 2.237))+", "+c.getTrafficRegime();
            
            
            if(c.hasDetector()){
                Detector det = c.getDetector();
                if(det.getLast30sDensity(time) >= 0 && det.getLast30sSpeed(time) >= 0){
                line += ", "+(det.getLast30sDensity(time) * 1609.34);
                line += ", "+(det.getLast30sSpeed(time) * 2.237);
                line += ", "+(det.getLast30sFlow(time) * 1609.34 * 2.237);
                }
                else{
                line += ", "+(det.getLast30sDensity(time));
                line += ", "+(det.getLast30sSpeed(time));
                line += ", "+(det.getLast30sFlow(time));
                }
            }
            
            if(c.hasInflowDet() && c.getInflowDet().getLast30sFlow(time) >= 0){
                line += ", "+(c.getInflowDet().getLast30sFlow(time) * 1609.34 * 2.237);
            }
            if(c.hasInflowDet() && c.getInflowDet().getLast30sFlow(time) < 0){
                line += ", "+(c.getInflowDet().getLast30sFlow(time));
            }
            
            if(c.hasOutflowDet() && c.getOutflowDet().getLast30sFlow(time) >= 0){
                line += ", "+(c.getOutflowDet().getLast30sFlow(time) * 1609.34 * 2.237);
            }
            
            if(c.hasOutflowDet() && c.getOutflowDet().getLast30sFlow(time) < 0){
                line += ", "+(c.getOutflowDet().getLast30sFlow(time));
            }
        }
        
        out.println(line);
    }
    
    public void nextTimestep(){
        
        predict(); // this populates P_t_tp and x_t_tp
        update(); // this populates P_t_t and x_t_t
        
        // this makes it easier to work with values later on
        // do I need to multithread running the kalman filter and the cell data processing?
        // if so, I will copy values from the cells into an array and process that array. Copying should be fast enough to not cause a synchronization error.
        saveValuesInCells(); // moves x_t_t values to cells
        
        time += dt;
    }
    
    private void predict(){
        //System.out.println("time = "+time);
        // vector of (k, v) per cell
        x_t_tp.set(0);
        
        
        int cell_idx = 0;
        
        for(Cell c : cells){
            
            
            double c_0 = calcC(c);
            double C = c_0*c_0;
                    
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
                /*
                if(c.outflow > k_i_t){
                    k_i_t = c.outflow;
                }
                */
            }
            
            double v_i_t = c.speed;
            double v_ip_t = 0; // in refers to i-next, ip is i-previous
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
                q_ip_t = k_ip_t * v_ip_t;
            }
            
            
            double k_in_t = (c.getNext() != null? c.getNext().density : c.density); 
            
            // equilibrium speed of cell i uses the density of cell i
            double eq_speed = c.getLink().getEquilibriumSpeed(k_i_t);
            
            double q_i_t = v_i_t * k_i_t;
            
            double k_i_tn = k_i_t + dt / dx1 * (q_ip_t - q_i_t); // density for cell i at time t+1
            
            /*if (c.getDetector() != null){
                if (Math.min(c.getDetector().getLast30sDensity(time), c.getLink().getK()) <= 0){
                k_i_tn = dt / dx1 * (q_ip_t - q_i_t);
            }
            }*/
            
            // assume added flow enters cell, so it increases occupancy (increases density)
            // assume counts on inflow/outflow are correct, and only counts are used, so 0 noise
            k_i_tn += (inflow - outflow) * dt / dx1;
            
            /*if (inflow > 0.0){
            System.out.println("\t   cell "+cell_idx+"   at time "+time);
            System.out.println("\t   inflow="+inflow);
            System.out.println("\t   outflow="+outflow);
            System.out.println("\t   diff="+(inflow - outflow));
            System.out.println("\t   dt="+dt);
            System.out.println("\t   dx1="+dx1);
            System.out.println("\t   dt/dx1="+ (dt / dx1));
            System.out.println("\t   ..................");
            }
            
            if (outflow > 0.0 && inflow <= 0.0){
            System.out.println("\t   cell "+cell_idx+"   at time "+time);
            System.out.println("\t   inflow="+inflow);
            System.out.println("\t   outflow="+outflow);
            System.out.println("\t   diff="+(inflow - outflow));
            System.out.println("\t   dt="+dt);
            System.out.println("\t   dx1="+dx1);
            System.out.println("\t   dt/dx1="+ (dt / dx1));
            System.out.println("\t   ..................");
            }*/
            
            // speed pressure term is becoming overlarge because k_i_t is small while k_in_t is large.
            double k_i_t_pressure = k_i_t;
            if(k_i_t < 0.001 && c.getNext() != null && c.getNext().hasDetector()){
                k_i_t_pressure = k_in_t;
            }
            
            // dt is in seconds, dx in meters, speeds in m/s, and tau in seconds, so no conversion factor is needed
            double v_i_tn = v_i_t - dt /dx1 * v_i_t * (v_i_t - v_ip_t) + dt * (eq_speed - v_i_t)/tau -
                    dt/dx2 * C / (k_i_t_pressure + chi) * (k_in_t - k_i_t_pressure);
            
            
            //System.out.println("\t   cell "+cell_idx);
            //System.out.println("\t   eq speed="+c.getLink().getEquilibriumSpeed(c.k_idx()));
            //System.out.println("\t   ......................");
            
            /*
            //System.out.println("check "+cell_idx+" "+k_i_tn+" "+v_i_tn);
            //System.out.println("\t"+cell_idx+"    "+(k_i_t*dx1)+"    "+(q_ip_t * dt)+"    "+(q_i_t * dt));  // no. of cars in cell i; no. of cars entering cell i; no. of cars leaving cell i
            System.out.println("\t"+cell_idx+"  v_i_t="+v_i_t+"   v_ip_t="+v_ip_t+"  diff="+(v_i_t - v_ip_t)+"   term1="+(dt /dx1 * v_i_t * (v_i_t - v_ip_t)));
            System.out.println("\t  eq_speed="+eq_speed +"  term2="+(dt * (eq_speed - v_i_t)/(tau)));
            System.out.println("\t  k_i_t="+k_i_t+"   k_in_t="+k_in_t+"   k_i_t_pressure="+k_i_t_pressure+"   C="+C+"     term3="+(dt/dx2 * C / (k_i_t + chi) * (k_in_t - k_i_t) ));
            System.out.println("\t  dt/dx1="+dt/dx1+"   q_ip_t="+q_ip_t+"   q_i_t="+q_i_t+"   term2k="+(dt/dx1 * (q_ip_t - q_i_t)));
            //System.out.println("\t"+cell_idx+"    "+k_i_tn+"    "+q_ip_t+"    "+q_i_t+"    "+k_i_t);
            //System.out.println("\t" +c.getLength());
            //System.out.println("\t" +(c.getNext() != null? c.getNext().getLength() : c.getLength()));
            System.out.println("\t........................");
            */
            
            
            // this prevents excessive values
            v_i_tn = Math.max(0, Math.min(v_i_tn, c.getLink().getMaxSpeed()));  // Math.min(v_i_tn, c.getLink().getMaxSpeed());
            k_i_tn = Math.max(0, Math.min(k_i_tn, c.getLink().getK()));  // Math.min(k_i_tn, c.getLink().getK());
            
            //System.out.println("\t    max speed="+c.getLink().getMaxSpeed());
            
            x_t_tp.setEntry(c.k_idx(), k_i_tn);
            x_t_tp.setEntry(c.v_idx(), v_i_tn);
            
            if(c.hasInflowDet()){
                x_t_tp.setEntry(c.inflow_idx(), inflow);
            }
            if(c.hasOutflowDet()){
                x_t_tp.setEntry(c.outflow_idx(), outflow);
            }
            
            /* System.out.println("\t   cell "+cell_idx);
            System.out.println("\t   speed="+v_i_t);
            System.out.println("\t   density="+k_i_t);
            System.out.println("\t   output density="+x_t_t.getEntry(c.k_idx()));
            System.out.println("\t   prev flow="+q_ip_t);
            System.out.println("\t   cell flow="+q_i_t);
            System.out.println("\t   difference="+(q_ip_t - q_i_t));
            System.out.println("\t   dt/dx="+dt/dx1);
            System.out.println("\t   k_i_tn="+(k_i_t + dt / dx1 * (q_ip_t - q_i_t)));
            System.out.println("\t   ................."); */
            
            // now need to calculate F, which is the Jacobian matrix of df/dx where f is the PW state update equation
            
            if(c.hasInflowDet()){
                F_t.setEntry(c.inflow_idx(), c.inflow_idx(), 1);
                F_t.setEntry(c.k_idx(), c.inflow_idx(), dt / dx1);
            }
            
            if(c.hasOutflowDet()){
                F_t.setEntry(c.outflow_idx(), c.outflow_idx(), 1);
                F_t.setEntry(c.k_idx(), c.outflow_idx(), -dt / dx1);
            }
            
            if(c.getPrev() != null){
                F_t.setEntry(c.k_idx(), c.k_idx(), 1 - dt /dx1 * c.speed);
            
                F_t.setEntry(c.k_idx(), c.v_idx(), -dt /dx1 * c.density);
            
                F_t.setEntry(c.k_idx(), c.getPrev().k_idx(), dt /dx1 * c.getPrev().speed);
                
                F_t.setEntry(c.k_idx(), c.getPrev().v_idx(), dt /dx1 * c.getPrev().density);
                
              
                
                /* F_t.setEntry(c.v_idx(), c.getPrev().v_idx(), dt * v_ip_t / dx2);  
                        
                F_t.setEntry(c.v_idx(), c.v_idx(), 1 - dt * v_i_t / dx2  - 1/tau); */
               
              
                // the convection term uses the length of cell i (dx1), same as the speed update above
                F_t.setEntry(c.v_idx(), c.getPrev().v_idx(), dt * v_i_t / dx1);
                F_t.setEntry(c.v_idx(), c.v_idx(), 1 - 2 * dt * v_i_t / dx1 - dt /tau + dt * v_ip_t / dx1);
                //System.out.println("\t    dx2="+dx2);
                      
                
            }
            else{
                F_t.setEntry(c.k_idx(), c.k_idx(), 1);
            
                F_t.setEntry(c.k_idx(), c.v_idx(), 0);
                
                F_t.setEntry(c.v_idx(), c.v_idx(), 1 - dt /tau);
            }
            
            
            
            // when k_i_t_pressure replaces k_i_t, the pressure term is 0 and does not depend on k_i_t or k_in_t
            boolean pressure_override = (k_i_t_pressure != k_i_t);

            if(c.getNext() != null && !pressure_override){
                /*F_t.setEntry(c.v_idx(), c.k_idx(), dt /dx2  * C / (k_i_t + chi) -
                        dt /dx2  * (k_in_t - k_i_t) * C / ((k_i_t + chi) * (k_i_t + chi))  );
                                
                F_t.setEntry(c.v_idx(), c.getNext().k_idx(), dt/dx2 * (c.getLink().getDerivEqSpeed(k_in_t)/tau - C / (k_i_t + chi)) );*/
                
                //F_t.setEntry(c.v_idx(), c.k_idx(), dt/dx2 * C * (k_in_t + chi) / ((k_i_t + chi) * (k_i_t + chi)) );
                
                
                // d/dk_i of -C (k_in - k_i)/(k_i + chi) is C (k_in + chi)/(k_i + chi)^2
                F_t.setEntry(c.v_idx(), c.k_idx(), dt * c.getLink().getDerivEqSpeed(k_i_t)/tau
                        + dt/dx2 * C * (k_in_t + chi) / ((k_i_t + chi) * (k_i_t + chi)));


                F_t.setEntry(c.v_idx(), c.getNext().k_idx(), - dt/dx2  * C / (k_i_t + chi) );

            }
            else{
                F_t.setEntry(c.v_idx(), c.k_idx(), dt * c.getLink().getDerivEqSpeed(k_i_t)/tau);

                if(c.getNext() != null){
                    F_t.setEntry(c.v_idx(), c.getNext().k_idx(), 0);
                }
            }
            
            cell_idx ++;
            
            /*if (c.hasDetector()){
            System.out.println("\t   cell "+cell_idx+"   flow="+(c.getDetector().getLast30sFlow(time)));
            }*/
            //System.out.println("\t   F_t="+F_t);
            /*System.out.println("\t   c.speed="+c.speed);
            System.out.println("\t   dx1="+dx1);
            System.out.println("\t   dt="+dt);
            System.out.println("\t   dt/dx1="+(dt/dx1));
            System.out.println("\t   F_t_1="+(1 - dt /dx1 * c.speed));*/
            //System.out.println("\t   );
            //System.out.println("\t   F_t_2="+(-dt /dx1 * c.density));
            //System.out.println("\t   F_t_3="+(dt /dx1 * c.getPrev().speed));
            //System.out.println("\t   F_t_4="+(dt /dx1 * c.getPrev().density));
            //System.out.println("\t   F_t_5="+(dt * v_i_t / dx2));
            //System.out.println("\t   F_t_6="+(1 - 2 * dt * v_i_t / dx2 - dt /tau + dt * v_ip_t / dx2));
            //System.out.println("\t   F_t_7="+(dt /dx2  * C / (k_i_t + chi) - 
                        //dt /dx2  * (k_in_t - k_i_t)* C / ((k_i_t + chi) * (k_i_t + chi))));
            //System.out.println("\t   F_t_8="+(dt/dx2 * (c.getLink().getDerivEqSpeed(k_in_t)/tau - C / (k_i_t + chi))));
            //System.out.println("\t   deriv eq speed="+(c.getLink().getDerivEqSpeed(k_in_t)));
            //System.out.println("\t   eq speed="+(eq_speed));
            /*System.out.println("\t   cell "+cell_idx);
            System.out.println("\t   speed="+v_i_t);
            System.out.println("\t   density="+k_i_t);
            System.out.println("\t   output density="+x_t_t.getEntry(c.k_idx()));
            System.out.println("\t   prev flow="+q_ip_t);
            System.out.println("\t   cell flow="+q_i_t);
            System.out.println("\t   difference="+(q_ip_t - q_i_t));
            System.out.println("\t   dt/dx="+dt/dx1);
            System.out.println("\t   k_i_tn="+(k_i_t + dt / dx1 * (q_ip_t - q_i_t)));
            System.out.println("\t   ................."); */
        }
        
        
        calcQ();
        // moving on to next time step, so t -> tp
        P_t_tp = F_t.multiply(P_t_t).multiply(F_t.transpose()).add(Q);
        
        //System.out.println("\t   P_t_t="+P_t_t);
        //System.out.println("\t   F_t.transpose="+F_t.transpose());
        //System.out.println("\t   Q="+Q);
        /*System.out.println("\t   R_t="+R_t);
        System.out.println("\t   F*P="+F_t.multiply(P_t_t));
        System.out.println("\t   F*P*F_t="+F_t.multiply(P_t_t).multiply(F_t.transpose()));*/
        
    }
    
    private double calcC(Cell cell){
        return C_0;  // m/s
        //return Math.min(20, Math.max(10, cell.getLink().getFFSpeed() / tau)); 
    }
    
 
    
    private void update(){
        // obtain detector data
        // only variables with a valid measurement in this interval are included in H; missing or bad data is -1
        List<Integer> observed = new ArrayList<>();
        List<Double> residuals = new ArrayList<>();
        
        for(Cell c : cells){
            Detector det = c.getDetector();
            
            if(c.hasDetector() && det.getLast30sDensity(time) >= 0 && det.getLast30sSpeed(time) >= 0){
                // this is the measurement residual
                double k_observed = Math.min(Math.max(det.getLast30sDensity(time), 0), c.getLink().getK());
                double v_observed = Math.min(Math.max(det.getLast30sSpeed(time), 0), c.getLink().getMaxSpeed());
                
                observed.add(c.k_idx());
                residuals.add(k_observed - x_t_tp.getEntry(c.k_idx()));
                
                observed.add(c.v_idx());
                residuals.add(v_observed - x_t_tp.getEntry(c.v_idx()));
            }
            
            // ramp detectors are separate from the mainline detector, so they are used even if the cell has no mainline detector
            if(c.hasInflowDet() && c.getInflowDet().getLast30sCount(time) >= 0){
                observed.add(c.inflow_idx());
                residuals.add(c.getInflow(time) - x_t_tp.getEntry(c.inflow_idx()));
            }
            if(c.hasOutflowDet() && c.getOutflowDet().getLast30sCount(time) >= 0){
                observed.add(c.outflow_idx());
                residuals.add(c.getOutflow(time) - x_t_tp.getEntry(c.outflow_idx()));
            }
        }
        
        if(observed.isEmpty()){
            // nothing measured in this interval, so the prediction is the estimate
            x_t_t = x_t_tp.copy();
            P_t_t = P_t_tp.copy();
            return;
        }
        
        calcR();
        
        int num_obs = observed.size();
        int size = x_t_tp.getDimension();
        
        // H selects the observed variables from the state vector
        RealMatrix H_t = new Array2DRowRealMatrix(num_obs, size);
        RealMatrix R_obs = new Array2DRowRealMatrix(num_obs, num_obs);
        y_t = new ArrayRealVector(num_obs);
        
        for(int j = 0; j < num_obs; j++){
            H_t.setEntry(j, observed.get(j), 1);
            y_t.setEntry(j, residuals.get(j));
            
            for(int l = 0; l < num_obs; l++){
                R_obs.setEntry(j, l, R_t.getEntry(observed.get(j), observed.get(l)));
            }
        }
        
        RealMatrix S_t = H_t.multiply(P_t_tp).multiply(H_t.transpose()).add(R_obs);
        
        // Kalman gain
        RealMatrix K_t = P_t_tp.multiply(H_t.transpose()).multiply(MatrixUtils.inverse(S_t));
        
        RealVector adjustment = K_t.operate(y_t);
        
        x_t_t = x_t_tp.add(adjustment);
        
        // negative densities are restricted to 0 in saveValuesInCells()
        if(printDiagnostics){
            for(Cell c : cells){
                if(x_t_t.getEntry(c.k_idx()) < -0.001){
                    printDiagnostics(c, observed, K_t, adjustment);
                }
            }
        }
        
        // Joseph form of update
        RealMatrix KH = K_t.multiply(H_t);
        P_t_t = I.subtract(KH).multiply(P_t_tp).multiply(I.subtract(KH).transpose()).add(K_t.multiply(R_obs).multiply(K_t.transpose()));
        
        P_t_t = P_t_t.add(P_t_t.transpose()).scalarMultiply(0.5);
    }
    
    // prints the Kalman gain, covariance, and Jacobian entries involved in a negative density estimate
    public boolean printDiagnostics = false;
    
    private void printDiagnostics(Cell c, List<Integer> observed, RealMatrix K_t, RealVector adjustment){
        System.out.println("k is "+x_t_t.getEntry(c.k_idx())+"  k_t_tp="+x_t_tp.getEntry(c.k_idx())+" cell_idx="+c.cell_idx+" adjustment="+adjustment.getEntry(c.k_idx()));
        System.out.println("v is "+x_t_t.getEntry(c.v_idx())+"  v_t_tp="+x_t_tp.getEntry(c.v_idx())+" cell_idx="+c.cell_idx+" adjustment="+adjustment.getEntry(c.v_idx()));
        
        for(int j = 0; j < observed.size(); j++){
            int i = observed.get(j);
            if(Math.abs(y_t.getEntry(j)) > 0.0001 && Math.abs(K_t.getEntry(c.k_idx(), j)) > 0.0001){
                System.out.println("K_t entry "+K_t.getEntry(c.k_idx(), j)+" y_t="+y_t.getEntry(j)+" x_t_tp="+x_t_tp.getEntry(i)+" var="+indices[i]);
            }
        }
        
        for(int i = 0; i < x_t_tp.getDimension(); i++){
            if(Math.abs(P_t_tp.getEntry(c.k_idx(), i)) > 0.0001){
                System.out.println("\t"+indices[c.k_idx()]+" P_t="+P_t_tp.getEntry(c.k_idx(), i)+" var="+indices[i]);
            }
            
            if(Math.abs(P_t_tp.getEntry(c.v_idx(), i)) > 0.0001){
                System.out.println("\t"+indices[c.v_idx()]+" P_t="+P_t_tp.getEntry(c.v_idx(), i)+" var="+indices[i]);
            }
        }
        
        for(Cell j : cells){
            if(F_t.getEntry(c.k_idx(), j.k_idx()) != 0){
                System.out.println("\tdk/dk "+j.cell_idx+"="+F_t.getEntry(c.k_idx(), j.k_idx())+" "+j.density);
            }
            if(F_t.getEntry(c.k_idx(), j.v_idx()) != 0){
                System.out.println("\tdk/dv "+j.cell_idx+"="+F_t.getEntry(c.k_idx(), j.v_idx())+" "+j.speed);
            }
            if(F_t.getEntry(c.v_idx(), j.k_idx()) != 0){
                System.out.println("\tdv/dk "+j.cell_idx+"="+F_t.getEntry(c.v_idx(), j.k_idx())+" "+j.density);
            }
            if(F_t.getEntry(c.v_idx(), j.v_idx()) != 0){
                System.out.println("\tdv/dv "+j.cell_idx+"="+F_t.getEntry(c.v_idx(), j.v_idx())+" "+j.speed);
            }
        }
    }
    
    private void saveValuesInCells(){
        for(Cell c : cells){
            c.density = Math.min(Math.max(x_t_t.getEntry(c.k_idx()), 0), c.getLink().getK());
            c.speed = Math.min(Math.max(x_t_t.getEntry(c.v_idx()), 0), c.getLink().getMaxSpeed());
            
            // ramp flows cannot be negative
            if(c.hasInflowDet()){
                c.inflow = Math.max(x_t_t.getEntry(c.inflow_idx()), 0);
            }
            if(c.hasOutflowDet()){
                c.outflow = Math.max(x_t_t.getEntry(c.outflow_idx()), 0);
            }
            /*System.out.println("\t   actual output density="+x_t_t.getEntry(c.k_idx()));
            System.out.println("\t   c.density="+c.density);
            System.out.println("\t ................");*/
            
        }
    }
    
    public void printCells(){
        System.out.println("t="+String.format("%.2f", time*1.0)+" --");
        for(Cell c : cells){
            System.out.println(c.k_idx()/2+"\t"+c.density+"\t"+c.speed+"\t"+c.getTrafficRegime());
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
        
        double scale = 1;
        
        double len_mean = 4.48;  // units of meters; 14.7 ft
        
        double len_stdev = 0.46; // stdev in vehicle length; 1.5 ft
        double len_var = len_stdev * len_stdev;
        
        
        
        for(Cell c : cells){
            if(c.hasDetector() && c.getDetector().getLast30sCount(time) > 0 && c.getDetector().getLast30sDensity(time) >= 0 && c.getDetector().getLast30sSpeed(time) >= 0){  // previously only hasDetector and Last30sCount > 0
                
                double q = c.getDetector().getLast30sFlow(time);
                double occ = len_mean / c.getDetector().getLast30sSpeed(time);
                
                // inflow/outflow is handled by a different detector, so assume 0 covariance
                // also assume 0 variance (perfect information)
                if(c.hasInflowDet()){
                    R_t.setEntry(c.inflow_idx(), c.inflow_idx(), 0);
                }
                if(c.hasOutflowDet()){
                    R_t.setEntry(c.outflow_idx(), c.outflow_idx(), 0);
                }
                
                R_t.setEntry(c.v_idx(), c.v_idx(), scale * len_var / (occ * occ)); // formula from Gemini
                
                
                double cov = 0; // this is intentionally set to 0
                
                R_t.setEntry(c.v_idx(), c.k_idx(), cov);
                R_t.setEntry(c.k_idx(), c.v_idx(), cov);
                
                R_t.setEntry(c.k_idx(), c.k_idx(), scale * len_var * q * q * occ * occ / (len_mean * len_mean * len_mean * len_mean)); // formula from Gemini
                
            }
            else 
            {
                // inflow/outflow is handled by a different detector, so assume 0 covariance
                // also assume 0 variance (perfect information)
                if(c.hasInflowDet()){
                    R_t.setEntry(c.inflow_idx(), c.inflow_idx(), 0);
                }
                if(c.hasOutflowDet()){
                    R_t.setEntry(c.outflow_idx(), c.outflow_idx(), 0);
                }
                
                R_t.setEntry(c.v_idx(), c.v_idx(), 0);
                
             
                R_t.setEntry(c.v_idx(), c.k_idx(), 0);
                R_t.setEntry(c.k_idx(), c.v_idx(), 0);
                
                R_t.setEntry(c.k_idx(), c.k_idx(), 0);
            }
        } 
        //System.out.println("\t  R_t="+R_t);
    }
    
    
    
    public void calcQ(){
        
        for(Cell c : cells){
            
            // covariance is likely negative: in congestion, larger k => smaller v
            // this is variance due to FD not fully describing traffic evolution
            // I don't know which values these should have
            double scale = 1e-3;
            double var_k = 5 * scale;
            double var_v = 1 * scale;
            double cov = 0; // this is intentionally set to 0
            
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
            
            //System.out.println("Q="+var_k+" "+var_v+" "+cov);
        }
            //System.out.println("\t   ..............");
    }
    
    public boolean addDetector(Detector det){
        for(Link l : links){
            if(l.addDetector(det)){
                return true;
            }
        }
        
        return false;
    }
    
    
    public void sortLinks(){
        // links may not be sorted...
        // Collections.sort didn't work!
        
        LinkedList<Link> ll = new LinkedList<>();
        
        ll.add(links.get(0));
        // assume that endpoints match
        while(ll.size() < links.size()){
            // can I find the matching link to the start endpoint?
            Coordinate start = ll.getFirst().getFirstCoordinate();
            
            for(Link l : links){
                if(l.getLastCoordinate().equals(start)){
                    ll.addFirst(l);
                    break;
                }
            }
            
            // if not (or even if so), what about the end endpoint?
            Coordinate end = ll.getLast().getLastCoordinate();
            for(Link l : links){
                if(l.getFirstCoordinate().equals(end)){
                    ll.addLast(l);
                    break;
                }
            }
        }
        
        // I don't want to use a LinkedList, convert back...
        links = new ArrayList<Link>(ll);
        
        double avg_bearing = 0;
        double total_weight = 0;
        
        for(Link l : links){
            double bearing = Coordinate.bearing(l.getFirstCoordinate(), l.getLastCoordinate());
            avg_bearing += bearing * l.getLength();
            total_weight += l.getLength();
        }
        
        avg_bearing = avg_bearing / total_weight;
        
        System.out.println(name+" avg direction: "+avg_bearing);
    }
}
