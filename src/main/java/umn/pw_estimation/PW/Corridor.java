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
 * One direction of a freeway, estimated with an extended Kalman filter (EKF) on the Payne-Whitham (PW) model.
 *
 * UNITS: everything inside this class is in meters, seconds, veh/m and m/s. Values are converted only when reading
 * input or printing output (1609.3 m per mile, and 1 m/s = 2.237 mph). Density k and flow q of a cell are TOTALS
 * across all lanes; divide by getNumLanes() for per-lane values.
 *
 * STATE VECTOR: the Kalman filter works on one long vector x with two entries per cell, density and speed, plus one
 * entry for each on-ramp (inflow) or off-ramp (outflow) detector:
 *     x = [k_0, v_0, (inflow_0), (outflow_0), k_1, v_1, ...]
 * c.k_idx(), c.v_idx(), c.inflow_idx() and c.outflow_idx() give the position of each cell's variables in x.
 *
 * NAMING: x_t_t and P_t_t are the estimate and its covariance after using the measurements at time t.
 * x_t_tp and P_t_tp are the prediction for the next time step, before its measurements are used.
 * In predict(), _i_t means cell i at time t, _ip_t the previous (upstream) cell, _in_t the next (downstream) cell,
 * and _tn the next time step.
 *
 * @author mlevin
 */
public class Corridor {
    
    // Payne-Whitham parameters. These are static, so they are shared by both directions.
    // The values below gave the lowest error at held-out detectors for 4/8/26 9-10 AM, with dt = 6 s in Main.
    public static double tau = 60; // speed adaptation term, in seconds
    // traffic pressure parameter c_0, m/s
    // Link uses this to choose the cell length, so it must be set BEFORE ReadData.readOSM() creates the links
    // in testing, larger c_0 gave larger errors, and with dt = 3 s, c_0 = 15-20 m/s created false jams on 610 EB
    public static double C_0 = 10;
    public static double chi = 0.1 / 1609.3; // avoid 0/0 in traffic pressure when two adjacent cells are empty, veh/m per lane (0.1 veh/mi per lane)

    // Kalman filter process noise: standard deviation of model error per time step
    // larger values trust the detectors more and the PW model less
    public static double sigma_k = 10 / 1609.3; // density, veh/m per lane (10 veh/mi per lane)
    public static double sigma_v = 3 / 2.237; // speed, m/s (3 mph)
    
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
    
    private String[] indices; // name of each entry of the state vector, e.g. "12 k" is the density of cell 12; used in printDiagnostics()
    
    // Q: process noise covariance, F_t: Jacobian of the PW update (state transition matrix), I: identity matrix
    // R_t: measurement noise covariance, y_t: measurement residuals (detector value minus prediction)
    private RealMatrix Q, F_t, I;
    private RealMatrix P_t_tp, P_t_t, R_t;
    private RealVector x_t_tp, x_t_t;
    private RealVector y_t;
    
    // sets up the state vector and the starting estimate at the given time (seconds from the start of the data)
    // must be called before estimate() or nextTimestep(), and after all detectors have been added
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

        // cells without a working detector start at the average density per lane and average speed of the working detectors
        double sum_density_lane = 0;
        double sum_speed = 0;
        int num_observed = 0;

        for(Cell c : cells){
            if(c.hasDetector() && c.getDetector().getLast30sDensity(time) >= 0 && c.getDetector().getLast30sSpeed(time) >= 0){
                sum_density_lane += Math.min(c.getDetector().getLast30sDensity(time), c.getLink().getK()) / c.getLink().getNumLanes();
                sum_speed += Math.min(c.getDetector().getLast30sSpeed(time), c.getLink().getMaxSpeed());
                num_observed++;
            }
        }

        for(Cell c : cells){
            if(num_observed > 0){
                c.density = sum_density_lane / num_observed * c.getLink().getNumLanes();
                c.speed = sum_speed / num_observed;
            }

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

        // P_t_t is the uncertainty of the starting estimate: small for cells with a detector (the measurement error), large otherwise
        // initial values in cells without a working detector are uncertain
        double init_sigma_k = 20 / 1609.3; // 20 veh/mi per lane
        double init_sigma_v = 10 / 2.237; // 10 mi/hr

        for(Cell c : cells){
            if(!(c.hasDetector() && c.getDetector().getLast30sDensity(time) >= 0 && c.getDetector().getLast30sSpeed(time) >= 0)){
                double sigma_k_cell = init_sigma_k * c.getLink().getNumLanes();
                P_t_t.setEntry(c.k_idx(), c.k_idx(), sigma_k_cell * sigma_k_cell);
                P_t_t.setEntry(c.v_idx(), c.v_idx(), init_sigma_v * init_sigma_v);
            }
        }

        Q = new Array2DRowRealMatrix(size, size);
        
        
    }
    
    // records[cell index][time step index] stores the estimate at every time step, filled in by estimate()
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
    
    // writes density, speed and regime for the crash cell and upstream_cells cells upstream of it, every timestep seconds
    // for num_timesteps before the crash. estimate() must have been run past crash_time first.
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
    
    // runs the filter from the current time until endTime (seconds), writing each time step to fileout if it is not null
    // note that this closes fileout when it is finished
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
    
    // one step of the extended Kalman filter: predict with the PW model, then correct with the detector data
    public void nextTimestep(){
        
        predict(); // this populates P_t_tp and x_t_tp
        update(); // this populates P_t_t and x_t_t
        
        // this makes it easier to work with values later on
        // do I need to multithread running the kalman filter and the cell data processing?
        // if so, I will copy values from the cells into an array and process that array. Copying should be fast enough to not cause a synchronization error.
        saveValuesInCells(); // moves x_t_t values to cells
        
        time += dt; // time is a long, so dt is rounded down if it is not a whole number
    }
    
    // EKF prediction step: x_t_tp = f(x_t_t), with f the discretized PW model (Section 2.1 of the report),
    // and P_t_tp = F P_t_t F^T + Q, with F the matrix of derivatives of f (Section 2.2 of the report)
    // the many commented-out System.out.println lines are old debugging output; uncomment them to trace one step
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
            
            // conservation of vehicles: density changes by (flow in from the previous cell - flow out) * dt / cell length
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
            
            // the traffic pressure term uses density per lane, so that a change in the number of lanes does not create a pressure gradient
            int lanes_i = c.getLink().getNumLanes();
            int lanes_in = (c.getNext() != null? c.getNext().getLink().getNumLanes() : lanes_i);
            double rho_i = k_i_t / lanes_i;
            double rho_in = k_in_t / lanes_in;

            // the pressure term (1/k) dP/dx = c_0^2 (dk/dx)/k divides by the average density of cells i and i+1,
            // so |rho_in - rho_i| / rho_avg <= 2 even when one cell is nearly empty; chi only prevents 0/0 when both are empty
            double rho_avg = (rho_i + rho_in) / 2 + chi;

            // dt is in seconds, dx in meters, speeds in m/s, and tau in seconds, so no conversion factor is needed
            // first apply convection and traffic pressure
            double v_star = v_i_t - dt /dx1 * v_i_t * (v_i_t - v_ip_t) -
                    dt/dx2 * C / rho_avg * (rho_in - rho_i);

            // then relax toward the equilibrium speed with the exact solution of du/dt = (V_e - u)/tau over one time step
            // this is stable for any dt/tau, unlike the explicit term dt * (eq_speed - v_i_t)/tau, which oscillates when dt > tau
            double decay = Math.exp(-dt / tau);
            double v_i_tn = eq_speed + (v_star - eq_speed) * decay;
            
            
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
            // F_t.setEntry(a, b, value) sets d(variable a at t+1) / d(variable b at t). Entries not set here stay 0.
            // If you change the update equations above, these derivatives must be changed to match, or the filter
            // will silently give wrong results. A quick check is to compare them with finite differences.
            
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
                // derivatives of v_star are multiplied by decay because v_i_tn = decay * v_star + (1 - decay) * eq_speed
                F_t.setEntry(c.v_idx(), c.getPrev().v_idx(), decay * dt * v_i_t / dx1);
                F_t.setEntry(c.v_idx(), c.v_idx(), decay * (1 - 2 * dt * v_i_t / dx1 + dt * v_ip_t / dx1));
                //System.out.println("\t    dx2="+dx2);
                      
                
            }
            else{
                F_t.setEntry(c.k_idx(), c.k_idx(), 1);
            
                F_t.setEntry(c.k_idx(), c.v_idx(), 0);
                
                F_t.setEntry(c.v_idx(), c.v_idx(), decay);
            }
            
            
            
            if(c.getNext() != null){
                /*F_t.setEntry(c.v_idx(), c.k_idx(), dt /dx2  * C / (k_i_t + chi) -
                        dt /dx2  * (k_in_t - k_i_t) * C / ((k_i_t + chi) * (k_i_t + chi))  );
                                
                F_t.setEntry(c.v_idx(), c.getNext().k_idx(), dt/dx2 * (c.getLink().getDerivEqSpeed(k_in_t)/tau - C / (k_i_t + chi)) );*/
                
                //F_t.setEntry(c.v_idx(), c.k_idx(), dt/dx2 * C * (k_in_t + chi) / ((k_i_t + chi) * (k_i_t + chi)) );
                
                
                // with g = (rho_in - rho_i)/rho_avg: dg/drho_i = -(rho_in + chi)/rho_avg^2 and dg/drho_in = (rho_i + chi)/rho_avg^2
                // drho_i/dk_i = 1/lanes_i and drho_in/dk_in = 1/lanes_in
                F_t.setEntry(c.v_idx(), c.k_idx(), (1 - decay) * c.getLink().getDerivEqSpeed(k_i_t)
                        + decay * dt/dx2 * C * (rho_in + chi) / (rho_avg * rho_avg) / lanes_i);


                F_t.setEntry(c.v_idx(), c.getNext().k_idx(), - decay * dt/dx2 * C * (rho_i + chi) / (rho_avg * rho_avg) / lanes_in );

            }
            else{
                // in the last cell, k_in_t is k_i_t, so the pressure term is 0
                F_t.setEntry(c.v_idx(), c.k_idx(), (1 - decay) * c.getLink().getDerivEqSpeed(k_i_t));
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
        // the prediction is less certain than the current estimate: the uncertainty spreads to neighboring cells through F, plus model error Q
        P_t_tp = F_t.multiply(P_t_t).multiply(F_t.transpose()).add(Q);
        
        //System.out.println("\t   P_t_t="+P_t_t);
        //System.out.println("\t   F_t.transpose="+F_t.transpose());
        //System.out.println("\t   Q="+Q);
        /*System.out.println("\t   R_t="+R_t);
        System.out.println("\t   F*P="+F_t.multiply(P_t_t));
        System.out.println("\t   F*P*F_t="+F_t.multiply(P_t_t).multiply(F_t.transpose()));*/
        
    }
    
    // c_0 for a cell; the same everywhere for now, but kept as a method so it could vary by cell later
    private double calcC(Cell cell){
        return C_0;  // m/s
        //return Math.min(20, Math.max(10, cell.getLink().getFFSpeed() / tau)); 
    }
    
 
    
    // EKF update (correction) step: combines the prediction with the detector data that are available at this time
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
        
        // Kalman gain: how much to move each variable toward the measurements
        // it is large when the prediction is uncertain (P_t_tp large) relative to the measurement error (R_obs)
        // cells without a detector are also corrected, through the covariance between them and the measured cells
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
        // mathematically equal to (I - KH) P_t_tp, but this form stays symmetric and positive definite despite rounding error
        RealMatrix KH = K_t.multiply(H_t);
        P_t_t = I.subtract(KH).multiply(P_t_tp).multiply(I.subtract(KH).transpose()).add(K_t.multiply(R_obs).multiply(K_t.transpose()));
        
        // average with the transpose to remove any small asymmetry from rounding
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
    
    // copies the estimate from the state vector into the cells, limited to physically possible values
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
    
    // measurement noise covariance R: how inaccurate the detector values are
    // loop detectors measure counts and occupancy; speed is computed from them with an assumed average vehicle length,
    // so the speed and density errors come from the variation in vehicle length (delta method approximation)
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
    
    
    
    // process noise covariance Q: how much the PW model's prediction is expected to be wrong in one time step
    public void calcQ(){
        
        for(Cell c : cells){
            
            // covariance is likely negative: in congestion, larger k => smaller v
            // this is variance due to FD not fully describing traffic evolution
            // density error scales with the number of lanes because density is summed across lanes
            double scale = 1e-3;
            double sigma_k_cell = sigma_k * c.getLink().getNumLanes();
            double var_k = sigma_k_cell * sigma_k_cell;
            double var_v = sigma_v * sigma_v;
            double cov = 0; // this is intentionally set to 0
            
            double cov_inflow = 5 * scale; // not used
            
            Q.setEntry(c.k_idx(), c.k_idx(), var_k);
            Q.setEntry(c.v_idx(), c.v_idx(), var_v);
            Q.setEntry(c.k_idx(), c.v_idx(), cov);
            Q.setEntry(c.v_idx(), c.k_idx(), cov);
            
            // inflow/outflow is a separate detector and behavior so assume 0 covariance
            // ramp flows are in veh/s, so the variance 1e-3 is a standard deviation of about 0.03 veh/s (110 veh/hr)
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
    
    
    // puts the links in driving order by matching the last coordinate of each link to the first coordinate of the next
    // starting from the first link in the list, it grows the chain at both ends until all links are included
    // if two links do not share an exact endpoint coordinate, this loop never ends
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
