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
 * c.k_idx(), c.v_idx(), c.inflow_idx() and c.outflow_idx() give the position of each cell's variables in x. Cells with an
 * unmetered ramp (see addRamp()) have one more variable, c.unmetered_idx().
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
    // tau and C_0 gave the lowest error at held-out detectors for 4/8/26 9-10 AM (uncongested), with dt = 3-4 s in Main, and
    // shorter tau (10 or 20 s) gave larger errors in leave-one-out validation on 9/2/26 3-6 PM (congested).
    public static double tau = 60; // speed adaptation term, in seconds
    // traffic pressure parameter c_0, m/s
    // buildCells() uses this to choose the cell length, so it must be set BEFORE ReadData.readOSM() adds the detectors
    // in testing, larger c_0 gave larger errors, and c_0 = 20 m/s created false jams on 610 EB
    public static double C_0 = 10;
    public static double chi = 0.1 / 1609.3; // avoid 0/0 in traffic pressure when two adjacent cells are empty, veh/m per lane (0.1 veh/mi per lane)

    // Kalman filter process noise: standard deviation of model error per time step
    // larger values trust the detectors more and the PW model less
    public static double sigma_k = 10 / 1609.3; // density, veh/m per lane (10 veh/mi per lane)
    public static double sigma_v = 6 / 2.237; // speed, m/s (6 mph); 6 mph gave lower errors than 3 mph at withheld stations on 9/2/26 3-6 PM

    // cell length as a multiple of the shortest length allowed by the CFL condition (see buildCells())
    public static double CELL_LENGTH_FACTOR = 1.5;
    
    private Cell[] cells; // in driving order; created by buildCells()
    
    // OpenStreetMap links, sorted in driving order by buildCells(); each link has a constant number of lanes
    private List<Link> links;
    private double[] link_start; // distance (m) from the start of the corridor to the start of each link
    
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
    
    
    
    // divides the corridor into cells of equal length, ignoring the boundaries between OpenStreetMap links
    // OpenStreetMap splits the road into links at bridges, ramps, etc., and some links are only about 20 m long.
    // Cells must be at least the CFL minimum length for stability, so each link cannot have its own cells:
    // a short link would become one cell that is longer than the link, which makes the modeled road too long.
    // A cell that covers parts of links with different numbers of lanes gets the length-weighted average number of lanes
    // (e.g. 150 m of 2 lanes and 50 m of 3 lanes gives 2.25 lanes), which keeps the total road space correct.
    // This runs once, the first time cells are needed (adding a detector, finding a location, or init()).
    private void buildCells(){
        if(cells != null){
            return;
        }
        
        sortLinks();
        
        link_start = new double[links.size()];
        double total_length = 0;
        double max_speed = 0;
        
        for(int j = 0; j < links.size(); j++){
            link_start[j] = total_length;
            total_length += links.get(j).getLength();
            max_speed = Math.max(max_speed, links.get(j).getMaxSpeed());
        }
        
        // CFL condition for the Payne-Whitham model: the fastest wave travels at speed + c_0, so it cannot cross more than one cell per time step
        // cells are CELL_LENGTH_FACTOR times longer than this minimum, because the PW discretization is only stable in free flow when
        // (speed + c_0) * dt / dx is below about 0.7 (von Neumann stability analysis); at the minimum length, small errors grow into false jams
        double min_length = CELL_LENGTH_FACTOR * (max_speed + C_0) * dt;
        
        // the largest number of equal cells that are all at least min_length long
        int num_cells = (int)Math.max(1, Math.floor(total_length / min_length));
        double cell_length = total_length / num_cells;
        
        cells = new Cell[num_cells];
        Cell prev = null;
        
        for(int i = 0; i < num_cells; i++){
            double start = i * cell_length;
            double end = start + cell_length;
            
            // lane-meters of road in this cell, from every link that overlaps it
            double lane_meters = 0;
            for(int j = 0; j < links.size(); j++){
                double overlap = Math.min(end, link_start[j] + links.get(j).getLength()) - Math.max(start, link_start[j]);
                if(overlap > 0){
                    lane_meters += overlap * links.get(j).getNumLanes();
                }
            }
            
            // the per-lane flow-density relationship comes from the link at the middle of the cell
            // (on MN-610 all links have the same per-lane values, so only the number of lanes differs)
            Link mid_link = links.get(findLinkIdx(start + cell_length / 2));
            
            cells[i] = new Cell(cell_length, lane_meters / cell_length, mid_link, prev);
            cells[i].cell_idx = i;
            prev = cells[i];
        }
    }
    
    // index of the link containing a distance (m) from the start of the corridor
    private int findLinkIdx(double position){
        for(int j = links.size() - 1; j > 0; j--){
            if(position >= link_start[j]){
                return j;
            }
        }
        return 0;
    }
    
    // the cell containing a distance (m) from the start of the corridor
    private Cell findCell(double position){
        int idx = (int)Math.floor(position / cells[0].getLength());
        return cells[Math.max(0, Math.min(cells.length - 1, idx))];
    }
    
    public Cell[] getCells(){
        buildCells();
        return cells;
    }
    
    // gives each cell's variables their positions in the state vector (see the comment at the top of this class)
    private void assignVariableIndices(){
        int var_idx = 0;
        
        for(Cell c : cells){
            c.set_k_idx(var_idx++);
            c.set_v_idx(var_idx++);
            
            if(c.hasInflowDet()){
                c.set_inflow_idx(var_idx++);
            }
            if(c.hasOutflowDet()){
                c.set_outflow_idx(var_idx++);
            }
            if(c.hasUnmeteredRamp()){
                c.set_unmetered_idx(var_idx++);
            }
        }
    }
    
    // Unmetered ramps. Comparing the flows at consecutive mainline stations with the ramp detectors between them shows that
    // several interchanges have ramps without detectors (e.g. the T.H. 252 ramps). Each such ramp is a variable of the state
    // vector: its flow is not measured, so the Kalman filter estimates it from the difference between the model and the
    // mainline detectors. Its model is a random walk, so it can change during the day. See addRamp().
    // If the historical average flow of the ramp by time of day is known (see addUnmeteredPrior()), the estimate instead
    // relaxes toward that average with time constant unmetered_reversion, unless the detectors show otherwise.
    public static double sigma_unmetered = 0.005; // veh/s per time step (18 veh/hr); random walk process noise
    public static double init_sigma_unmetered = 0.3; // veh/s (1080 veh/hr); uncertainty of the initial value of 0
    public static double max_unmetered = 2400 / 3600.0; // veh/s (2400 veh/hr); largest flow of any ramp, metered or not
    public static double max_sigma_unmetered = 500 / 3600.0; // veh/s (500 veh/hr); largest standard deviation, see boundCovariance()
    // with a larger limit (1800 veh/hr), unmetered ramps in a long stretch without working detectors grew to absorb errors
    // caused by queues from downstream (e.g. 610 WB from University Ave to East River Rd with T.H. 47 withheld)
    public static boolean UNMETERED_RAMPS = true; // false ignores the unmetered ramps
    // seconds; with sigma_unmetered, the estimate varies about 180 veh/hr (standard deviation) around the historical average
    // Without the historical average, the unmetered exit between Hemlock Ln and Elm Creek Blvd (610 WB) was estimated at
    // 200-500 veh/hr when the Elm Creek Blvd station was withheld, while the flow balance on 25 other weekdays gives
    // 450-600 veh/hr from 3 to 6 PM; the extra vehicles made the queue upstream of Fernbrook Ln too long and too dense.
    public static double unmetered_reversion = 600;
    
    // time of day (seconds after midnight) at time 0 of the detector data; used for the historical averages of unmetered ramps
    private long clock_start = 0;
    
    public void setClockStart(long clock_start){
        this.clock_start = clock_start;
    }
    
    // the cell that a ramp's merge or diverge point is in, or the nearest cell within 100 ft; null if not on this corridor
    public Cell findRampCell(Coordinate point){
        Cell cell = findCell(point);
        if(cell == null){
            cell = findNearestCell(point, 100);
        }
        return cell;
    }
    
    // historical average flow (veh/s; positive entering) by time of day of the unmetered ramp at this merge or diverge point,
    // in bins of Cell.PRIOR_BIN seconds starting at midnight, NaN where unknown; returns false if there is no unmetered ramp
    // there. Made by UnmeteredPrior from the flow balance between mainline stations on other days.
    public boolean addUnmeteredPrior(Coordinate point, double[] profile){
        Cell cell = findRampCell(point);
        if(cell == null || !cell.hasUnmeteredRamp()){
            return false;
        }
        cell.addUnmeteredPrior(profile);
        return true;
    }
    
    // adds an entrance or exit ramp whose merge or diverge point is at a latitude/longitude (on this corridor), with the
    // detector that counts its flow, or null for an unmetered ramp; returns false if the point is not on this corridor
    public boolean addRamp(Coordinate point, boolean entrance, Detector det){
        Cell cell = findRampCell(point);
        if(cell == null){
            return false;
        }
        
        if(det == null){
            if(UNMETERED_RAMPS){
                cell.addUnmeteredRamp(entrance? 1 : -1);
            }
        }
        else if(entrance){
            cell.addInflowDet(cell.hasInflowDet()? new DetectorGroup(new Detector[]{cell.getInflowDet(), det}) : det);
        }
        else{
            cell.addOutflowDet(cell.hasOutflowDet()? new DetectorGroup(new Detector[]{cell.getOutflowDet(), det}) : det);
        }
        return true;
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
        
        buildCells();
        
        this.time = time;
        
        assignVariableIndices();
        
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
            
            if(c.hasUnmeteredRamp()){
                indices[c.unmetered_idx()] = c.cell_idx+" unmetered";
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
                sum_density_lane += Math.min(getObservedDensity(c, time), c.getK()) / c.getNumLanes();
                sum_speed += Math.min(c.getDetector().getLast30sSpeed(time), c.getMaxSpeed());
                num_observed++;
            }
        }

        for(Cell c : cells){
            if(num_observed > 0){
                c.density = sum_density_lane / num_observed * c.getNumLanes();
                c.speed = sum_speed / num_observed;
            }

            if(c.hasDetector() && c.getDetector().getLast30sDensity(time) >= 0 && c.getDetector().getLast30sSpeed(time) >= 0){  // previously only hasDetector
                // values of -1 indicates bad data or missing data
                double k_observed = Math.min(Math.max(getObservedDensity(c, time), 0), c.getK());
                double v_observed = Math.min(Math.max(c.getDetector().getLast30sSpeed(time), 0), c.getMaxSpeed());
                
                if(k_observed >= 0 && v_observed >= 0){  // change to strictly inequality?
                    c.density = k_observed;
                    c.speed = v_observed;  // already in m/s; no need for unit conversions
                }
                else{
                    c.density = 0;
                    c.speed = c.getFFSpeed();
                }
                //System.out.println("\t   ff speed= "+c.getFFSpeed());
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
            
            if(c.hasUnmeteredRamp()){
                // the historical average if known, otherwise 0
                double prior = c.getUnmeteredPrior(clock_start + time);
                c.unmetered = Double.isNaN(prior)? 0 : prior;
                x_t_t.setEntry(c.unmetered_idx(), c.unmetered);
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
                double sigma_k_cell = init_sigma_k * c.getNumLanes();
                P_t_t.setEntry(c.k_idx(), c.k_idx(), sigma_k_cell * sigma_k_cell);
                P_t_t.setEntry(c.v_idx(), c.v_idx(), init_sigma_v * init_sigma_v);
            }
            if(c.hasUnmeteredRamp()){
                P_t_t.setEntry(c.unmetered_idx(), c.unmetered_idx(), init_sigma_unmetered * init_sigma_unmetered);
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
            header1 += ",cell "+(start_cell + i)+",,";
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
    
    // largest distance (ft) from a ramp detector to the corridor
    public static double MAX_RAMP_DISTANCE = 1000;
    
    // the cell nearest to a latitude/longitude, or null if it is more than max_dist (ft) from the corridor
    public Cell findNearestCell(Coordinate location, double max_dist){
        double position = findPosition(location, max_dist);
        return Double.isNaN(position)? null : findCell(position);
    }
    
    // distance (m) from the start of the corridor to the point of the corridor nearest to a latitude/longitude, or NaN if
    // it is more than max_dist (ft) from the corridor
    public double findPosition(Coordinate location, double max_dist){
        buildCells();
        
        double best_dist = max_dist;
        double best = Double.NaN;
        for(int j = 0; j < links.size(); j++){
            double[] nearest = links.get(j).findNearestPosition(location);
            if(nearest[0] <= best_dist){
                best_dist = nearest[0];
                best = link_start[j] + nearest[1];
            }
        }
        return best;
    }
    
    // the cell containing a latitude/longitude, or null if it is not on this corridor
    public Cell findCell(Coordinate location){
        buildCells();
        
        for(int j = 0; j < links.size(); j++){
            double position = links.get(j).findPosition(location);
            
            if(position >= 0){
                return findCell(link_start[j] + position);
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
            if(c.hasUnmeteredRamp()){
                header1 += ", unmetered ramp";
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
            line += ", "+(c.density * 1609.34)+", "+(c.speed * 2.23694)+", "+((c.density * 1609.34) * (c.speed * 2.23694))+", "+c.getTrafficRegime();
            
            
            if(c.hasDetector()){
                Detector det = c.getDetector();
                // detector density and flow are converted to the cell's number of lanes, so they can be compared with the estimate
                if(det.getLast30sDensity(time) >= 0 && det.getLast30sSpeed(time) >= 0){
                line += ", "+(getObservedDensity(c, time) * 1609.34);
                line += ", "+(det.getLast30sSpeed(time) * 2.23694);
                line += ", "+(det.getLast30sFlow(time) * c.getNumLanes() / det.getNumLanes() * 3600);
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
            
            // estimated unmetered net inflow, veh/hr
            if(c.hasUnmeteredRamp()){
                line += ", "+(c.unmetered * 3600);
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

        // F is filled in from scratch at every time step
        F_t = new Array2DRowRealMatrix(x_t_tp.getDimension(), x_t_tp.getDimension());

        // flows between cells, limited by demand and supply (see calcFlux())
        // flux[i] is the flow into cell i, and flux[i+1] the flow out of cell i
        Flux[] flux = new Flux[cells.length + 1];
        for(int i = 0; i <= cells.length; i++){
            // the first cell's inflow is its own demand, limited by its own supply; the last cell's outflow is its demand
            Cell sender = cells[Math.max(0, i - 1)];
            Cell receiver = (i < cells.length? cells[i] : null);
            flux[i] = calcFlux(sender, receiver);
        }

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
            double eq_speed = c.getEquilibriumSpeed(k_i_t);
            
            // conservation of vehicles: density changes by (flow in from the previous cell - flow out) * dt / cell length
            // the flows are k * v, limited by capacity and by the space available in the receiving cell (see calcFlux())
            double k_i_tn = k_i_t + dt / dx1 * (flux[cell_idx].value - flux[cell_idx + 1].value); // density for cell i at time t+1
            
            /*if (c.getDetector() != null){
                if (Math.min(c.getDetector().getLast30sDensity(time), c.getK()) <= 0){
                k_i_tn = dt / dx1 * (q_ip_t - q_i_t);
            }
            }*/
            
            // assume added flow enters cell, so it increases occupancy (increases density)
            // assume counts on inflow/outflow are correct, and only counts are used, so 0 noise
            k_i_tn += (inflow - outflow) * dt / dx1;
            
            // the unmetered ramp adds its estimated net inflow in the same way
            if(c.hasUnmeteredRamp()){
                k_i_tn += c.unmetered * dt / dx1;
            }
            
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
            double lanes_i = c.getNumLanes();
            double lanes_in = (c.getNext() != null? c.getNext().getNumLanes() : lanes_i);
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
            //System.out.println("\t   eq speed="+c.getEquilibriumSpeed(c.k_idx()));
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
            // if a value is limited, its derivatives are 0 (see the end of this loop)
            boolean v_limited = v_i_tn < 0 || v_i_tn > c.getMaxSpeed();
            boolean k_limited = k_i_tn < 0 || k_i_tn > c.getK();
            v_i_tn = Math.max(0, Math.min(v_i_tn, c.getMaxSpeed()));  // Math.min(v_i_tn, c.getMaxSpeed());
            k_i_tn = Math.max(0, Math.min(k_i_tn, c.getK()));  // Math.min(k_i_tn, c.getK());
            
            //System.out.println("\t    max speed="+c.getMaxSpeed());
            
            x_t_tp.setEntry(c.k_idx(), k_i_tn);
            x_t_tp.setEntry(c.v_idx(), v_i_tn);
            
            if(c.hasInflowDet()){
                x_t_tp.setEntry(c.inflow_idx(), inflow);
            }
            if(c.hasOutflowDet()){
                x_t_tp.setEntry(c.outflow_idx(), outflow);
            }
            if(c.hasUnmeteredRamp()){
                x_t_tp.setEntry(c.unmetered_idx(), predictUnmetered(c));
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
            
            if(c.hasUnmeteredRamp()){
                F_t.setEntry(c.unmetered_idx(), c.unmetered_idx(), unmeteredDecay(c));
                F_t.setEntry(c.k_idx(), c.unmetered_idx(), dt / dx1);
            }
            
            // density: k_i_tn = k_i_t + dt/dx1 * (flux into cell i - flux out of cell i)
            F_t.addToEntry(c.k_idx(), c.k_idx(), 1);
            flux[cell_idx].addDerivatives(c.k_idx(), dt / dx1);
            flux[cell_idx + 1].addDerivatives(c.k_idx(), -dt / dx1);

            if(c.getPrev() != null){
                /* F_t.setEntry(c.v_idx(), c.getPrev().v_idx(), dt * v_ip_t / dx2);  
                        
                F_t.setEntry(c.v_idx(), c.v_idx(), 1 - dt * v_i_t / dx2  - 1/tau); */
               
              
                // the convection term uses the length of cell i (dx1), same as the speed update above
                // derivatives of v_star are multiplied by decay because v_i_tn = decay * v_star + (1 - decay) * eq_speed
                F_t.setEntry(c.v_idx(), c.getPrev().v_idx(), decay * dt * v_i_t / dx1);
                F_t.setEntry(c.v_idx(), c.v_idx(), decay * (1 - 2 * dt * v_i_t / dx1 + dt * v_ip_t / dx1));
                //System.out.println("\t    dx2="+dx2);
                      
                
            }
            else{
                F_t.setEntry(c.v_idx(), c.v_idx(), decay);
            }
            
            
            
            if(c.getNext() != null){
                /*F_t.setEntry(c.v_idx(), c.k_idx(), dt /dx2  * C / (k_i_t + chi) -
                        dt /dx2  * (k_in_t - k_i_t) * C / ((k_i_t + chi) * (k_i_t + chi))  );
                                
                F_t.setEntry(c.v_idx(), c.getNext().k_idx(), dt/dx2 * (c.getDerivEqSpeed(k_in_t)/tau - C / (k_i_t + chi)) );*/
                
                //F_t.setEntry(c.v_idx(), c.k_idx(), dt/dx2 * C * (k_in_t + chi) / ((k_i_t + chi) * (k_i_t + chi)) );
                
                
                // with g = (rho_in - rho_i)/rho_avg: dg/drho_i = -(rho_in + chi)/rho_avg^2 and dg/drho_in = (rho_i + chi)/rho_avg^2
                // drho_i/dk_i = 1/lanes_i and drho_in/dk_in = 1/lanes_in
                F_t.setEntry(c.v_idx(), c.k_idx(), (1 - decay) * c.getDerivEqSpeed(k_i_t)
                        + decay * dt/dx2 * C * (rho_in + chi) / (rho_avg * rho_avg) / lanes_i);


                F_t.setEntry(c.v_idx(), c.getNext().k_idx(), - decay * dt/dx2 * C * (rho_i + chi) / (rho_avg * rho_avg) / lanes_in );

            }
            else{
                // in the last cell, k_in_t is k_i_t, so the pressure term is 0
                F_t.setEntry(c.v_idx(), c.k_idx(), (1 - decay) * c.getDerivEqSpeed(k_i_t));
            }

            // a value limited to [0, K] or [0, max speed] does not change with small changes in the previous state, so its
            // derivatives are 0. Without this, a cell limited at 0 density keeps the very large derivative of the pressure term
            // (which divides by the density), and the covariance grows without bound.
            if(k_limited){
                zeroRow(c.k_idx());
            }
            if(v_limited){
                zeroRow(c.v_idx());
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
            //System.out.println("\t   F_t_8="+(dt/dx2 * (c.getDerivEqSpeed(k_in_t)/tau - C / (k_i_t + chi))));
            //System.out.println("\t   deriv eq speed="+(c.getDerivEqSpeed(k_in_t)));
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
        P_t_tp = multiplyFPFt(P_t_t).add(Q);
        boundCovariance(P_t_tp);

        //System.out.println("\t   P_t_t="+P_t_t);
        //System.out.println("\t   F_t.transpose="+F_t.transpose());
        //System.out.println("\t   Q="+Q);
        /*System.out.println("\t   R_t="+R_t);
        System.out.println("\t   F*P="+F_t.multiply(P_t_t));
        System.out.println("\t   F*P*F_t="+F_t.multiply(P_t_t).multiply(F_t.transpose()));*/
        
    }
    
    // flow between two cells (veh/s) and its derivatives with respect to the state
    // The flow from a sending cell to a receiving cell is the smallest of
    //   k * v of the sending cell (the PW flow),
    //   the capacity of the sending cell (its demand cannot exceed capacity; in congestion, the queue discharge rate), and
    //   the supply of the receiving cell, the flow it can accept: the capacity in free flow and the congested flow in congestion.
    // This is the demand-supply (Godunov) flux of the cell transmission model. Without the last two limits, the PW density
    // equation moves k * v vehicles even into a cell at jam density, which created queues at jam density moving at 20 mi/hr
    // (flows above 3000 veh/hr/ln) between detectors.
    private class Flux {
        double value;
        Cell sender, receiver;
        double d_k_sender, d_v_sender, d_k_receiver;

        // adds coeff * (derivatives of this flow) to row of F_t
        void addDerivatives(int row, double coeff){
            if(d_k_sender != 0){
                F_t.addToEntry(row, sender.k_idx(), coeff * d_k_sender);
            }
            if(d_v_sender != 0){
                F_t.addToEntry(row, sender.v_idx(), coeff * d_v_sender);
            }
            if(d_k_receiver != 0){
                F_t.addToEntry(row, receiver.k_idx(), coeff * d_k_receiver);
            }
        }
    }
    
    // if true, entrance ramp flows take part of the supply of their cell (see calcFlux())
    public static boolean MERGE_SUPPLY = true;

    // receiver is null for the flow out of the last cell, which is limited only by the demand of the last cell
    private Flux calcFlux(Cell sender, Cell receiver){
        Flux f = new Flux();
        f.sender = sender;
        f.receiver = receiver;

        double pw_flow = sender.density * sender.speed;
        double capacity = sender.getDemandCap(sender.density);
        double supply = (receiver != null? receiver.getSupply(receiver.density) : Double.MAX_VALUE);
        
        // entrance ramp flows into the receiver use part of its supply, so the mainline gets the rest (a merge); without
        // this, the ramp flow entered even when the cell was full, and the C.S.A.H. 81 entrance (610 WB) filled its cell to
        // jam density. The derivatives with respect to the ramp flows are left out. On 9/2/26 3-6 PM, this lowered the
        // density error at withheld stations from 13.0 to 12.6 veh/mi/ln (from 28 to 12 at 610 EB/Regent Ave).
        // Also letting a congested cell accept as much as its outflow, when that is more than its congested flow, fixed the
        // false queue at 610 WB/Elm Creek Blvd but created queues at jam density elsewhere and raised the error to 14.4.
        if(MERGE_SUPPLY && receiver != null){
            double entering = receiver.inflow + (receiver.hasUnmeteredRamp()? Math.max(receiver.unmetered, 0) : 0);
            supply = Math.max(supply - entering, 0);
        }

        if(pw_flow <= capacity && pw_flow <= supply){
            f.value = pw_flow;
            f.d_k_sender = sender.speed;
            f.d_v_sender = sender.density;
        }
        else if(capacity <= supply){
            f.value = capacity;
        }
        else{
            f.value = supply;
            f.d_k_receiver = receiver.getDerivSupply(receiver.density);
        }

        return f;
    }

    // fraction of the difference from the historical average that remains after one time step; 1 (random walk) if the
    // historical average is unknown
    private double unmeteredDecay(Cell c){
        if(Double.isNaN(c.getUnmeteredPrior(clock_start + time)) || Double.isNaN(c.getUnmeteredPrior(clock_start + time + dt))){
            return 1;
        }
        return Math.exp(-dt / unmetered_reversion);
    }
    
    // predicted unmetered flow: m(t+dt) + decay * (flow - m(t)), with m the historical average
    private double predictUnmetered(Cell c){
        double decay = unmeteredDecay(c);
        if(decay == 1){
            return c.unmetered;
        }
        double m_t = c.getUnmeteredPrior(clock_start + time);
        double m_tn = c.getUnmeteredPrior(clock_start + time + dt);
        return m_tn + decay * (c.unmetered - m_t);
    }
    
    // sets every entry of one row of F_t to 0
    private void zeroRow(int row){
        for(int j = 0; j < F_t.getColumnDimension(); j++){
            F_t.setEntry(row, j, 0);
        }
    }

    // F_t P F_t^T, using only the nonzero entries of F_t (at most 6 per row), which is much faster than dense multiplication
    private RealMatrix multiplyFPFt(RealMatrix P){
        int n = P.getRowDimension();
        double[][] F = ((Array2DRowRealMatrix)F_t).getDataRef();
        double[][] p = P.getData();

        // nonzero columns of each row of F
        int[][] nz = new int[n][];
        for(int r = 0; r < n; r++){
            int count = 0;
            for(int c = 0; c < n; c++){
                if(F[r][c] != 0){
                    count++;
                }
            }
            nz[r] = new int[count];
            count = 0;
            for(int c = 0; c < n; c++){
                if(F[r][c] != 0){
                    nz[r][count++] = c;
                }
            }
        }

        // A = F P
        double[][] A = new double[n][n];
        for(int r = 0; r < n; r++){
            for(int c : nz[r]){
                double f = F[r][c];
                double[] row = p[c];
                double[] out = A[r];
                for(int j = 0; j < n; j++){
                    out[j] += f * row[j];
                }
            }
        }

        // B = A F^T
        double[][] B = new double[n][n];
        for(int i = 0; i < n; i++){
            double[] a = A[i];
            double[] out = B[i];
            for(int r = 0; r < n; r++){
                double sum = 0;
                for(int c : nz[r]){
                    sum += a[c] * F[r][c];
                }
                out[r] = sum;
            }
        }

        return new Array2DRowRealMatrix(B, false);
    }

    // largest standard deviations allowed in the predicted covariance: density per lane (veh/m) and speed (m/s)
    // Unobserved cells have no measurement to reduce their uncertainty, and the linearized PW model can make it grow without
    // bound, especially where the PW model is unstable in dense traffic (see "Speed equation parameters" in Section 3.1 of the report). A variance far larger than
    // the range of possible values makes the Kalman gain extremely large, and the next update then moves those cells to 0 or
    // jam density. Each variable whose variance exceeds the limit has its row and column of P scaled down, which keeps the
    // correlations and keeps P positive semidefinite.
    public static double max_sigma_k = 50 / 1609.3; // 50 veh/mi per lane
    public static double max_sigma_v = 25 / 2.237; // 25 mi/hr

    private void boundCovariance(RealMatrix P){
        int n = P.getRowDimension();
        double[] scale = new double[n];
        boolean any = false;

        for(int i = 0; i < n; i++){
            scale[i] = 1;
        }

        for(Cell c : cells){
            double max_k = max_sigma_k * c.getNumLanes();
            double var_k = P.getEntry(c.k_idx(), c.k_idx());
            if(var_k > max_k * max_k){
                scale[c.k_idx()] = max_k / Math.sqrt(var_k);
                any = true;
            }
            double var_v = P.getEntry(c.v_idx(), c.v_idx());
            if(var_v > max_sigma_v * max_sigma_v){
                scale[c.v_idx()] = max_sigma_v / Math.sqrt(var_v);
                any = true;
            }
            // ramp flows, metered or not
            for(int idx : new int[]{c.unmetered_idx(), c.inflow_idx(), c.outflow_idx()}){
                if(idx >= 0){
                    double var_r = P.getEntry(idx, idx);
                    if(var_r > max_sigma_unmetered * max_sigma_unmetered){
                        scale[idx] = max_sigma_unmetered / Math.sqrt(var_r);
                        any = true;
                    }
                }
            }
        }

        if(!any){
            return;
        }

        double[][] p = ((Array2DRowRealMatrix)P).getDataRef();
        for(int i = 0; i < n; i++){
            for(int j = 0; j < n; j++){
                p[i][j] *= scale[i] * scale[j];
            }
        }
    }

    // c_0 for a cell; the same everywhere for now, but kept as a method so it could vary by cell later
    private double calcC(Cell cell){
        return C_0;  // m/s
        //return Math.min(20, Math.max(10, cell.getLink().getFFSpeed() / tau)); 
    }
    
 
    
    // density measured by the cell's mainline detector, converted to the cell's number of lanes (veh/m), or -1 if missing
    // A detector station can have a different number of lanes than the cell: the cell may have an average number of lanes
    // (see buildCells()), and some stations have loops in auxiliary lanes that OpenStreetMap does not count.
    // The detector's density per lane is therefore multiplied by the cell's number of lanes.
    private double getObservedDensity(Cell c, long time){
        Detector det = c.getDetector();
        double k = det.getLast30sDensity(time);
        
        if(k < 0){
            return -1;
        }
        return k / det.getNumLanes() * c.getNumLanes();
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
                double k_observed = Math.min(Math.max(getObservedDensity(c, time), 0), c.getK());
                double v_observed = Math.min(Math.max(det.getLast30sSpeed(time), 0), c.getMaxSpeed());
                
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
        
        // H selects the observed variables from the state vector: row j has a 1 in column observed.get(j)
        // H is not stored as a matrix; see below
        RealMatrix R_obs = new Array2DRowRealMatrix(num_obs, num_obs);
        y_t = new ArrayRealVector(num_obs);

        for(int j = 0; j < num_obs; j++){
            y_t.setEntry(j, residuals.get(j));
            
            for(int l = 0; l < num_obs; l++){
                R_obs.setEntry(j, l, R_t.getEntry(observed.get(j), observed.get(l)));
            }
        }
        
        // multiplying by H or H^T selects rows or columns, which is much faster than multiplying by H
        int[] obs = new int[num_obs];
        int[] all = new int[size];
        for(int j = 0; j < num_obs; j++){
            obs[j] = observed.get(j);
        }
        for(int j = 0; j < size; j++){
            all[j] = j;
        }
        RealMatrix PHt = P_t_tp.getSubMatrix(all, obs); // P_t_tp H^T

        RealMatrix S_t = P_t_tp.getSubMatrix(obs, obs).add(R_obs); // H P_t_tp H^T + R

        // Kalman gain: how much to move each variable toward the measurements
        // it is large when the prediction is uncertain (P_t_tp large) relative to the measurement error (R_obs)
        // cells without a detector are also corrected, through the covariance between them and the measured cells
        RealMatrix K_t = PHt.multiply(MatrixUtils.inverse(S_t));
        
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
        // computed as X = (I - KH) P_t_tp = P_t_tp - K (H P_t_tp), then X (I - KH)^T = X - (X H^T) K^T
        RealMatrix X = P_t_tp.subtract(K_t.multiply(P_t_tp.getSubMatrix(obs, all)));
        P_t_t = X.subtract(X.getSubMatrix(all, obs).multiply(K_t.transpose())).add(K_t.multiply(R_obs).multiply(K_t.transpose()));
        
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
            c.density = Math.min(Math.max(x_t_t.getEntry(c.k_idx()), 0), c.getK());
            c.speed = Math.min(Math.max(x_t_t.getEntry(c.v_idx()), 0), c.getMaxSpeed());
            
            // ramp flows cannot be negative or more than a ramp can carry
            if(c.hasInflowDet()){
                c.inflow = Math.min(Math.max(x_t_t.getEntry(c.inflow_idx()), 0), max_unmetered);
            }
            if(c.hasOutflowDet()){
                c.outflow = Math.min(Math.max(x_t_t.getEntry(c.outflow_idx()), 0), max_unmetered);
            }
            // an unmetered entrance cannot have a negative flow, and an exit cannot have a positive one
            if(c.hasUnmeteredRamp()){
                double lo = c.getUnmeteredType() == 1? 0 : -max_unmetered;
                double hi = c.getUnmeteredType() == -1? 0 : max_unmetered;
                c.unmetered = Math.max(lo, Math.min(hi, x_t_t.getEntry(c.unmetered_idx())));
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
                
                // flow converted to the cell's number of lanes, like the density in getObservedDensity()
                double q = c.getDetector().getLast30sFlow(time) * c.getNumLanes() / c.getDetector().getNumLanes();
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
            double sigma_k_cell = sigma_k * c.getNumLanes();
            double var_k = sigma_k_cell * sigma_k_cell;
            double var_v = sigma_v * sigma_v;
            double cov = 0; // this is intentionally set to 0
            
            double cov_inflow = 5 * scale; // not used
            
            Q.setEntry(c.k_idx(), c.k_idx(), var_k);
            Q.setEntry(c.v_idx(), c.v_idx(), var_v);
            Q.setEntry(c.k_idx(), c.v_idx(), cov);
            Q.setEntry(c.v_idx(), c.k_idx(), cov);
            
            // inflow/outflow is a separate detector and behavior so assume 0 covariance
            // ramp flows are random walks, like unmetered ramps; this matters when a ramp detector has no data, because the
            // ramp flow is then estimated like an unmetered ramp. An earlier variance of 1e-3 (a standard deviation of
            // 110 veh/hr per time step) let such ramp flows drift to several thousand veh/hr.
            if(c.hasInflowDet()){
                Q.setEntry(c.inflow_idx(), c.inflow_idx(), sigma_unmetered * sigma_unmetered);
            }
            
            if(c.hasOutflowDet()){
                Q.setEntry(c.outflow_idx(), c.outflow_idx(), sigma_unmetered * sigma_unmetered);
            }
            
            if(c.hasUnmeteredRamp()){
                Q.setEntry(c.unmetered_idx(), c.unmetered_idx(), sigma_unmetered * sigma_unmetered);
            }
            
            //System.out.println("Q="+var_k+" "+var_v+" "+cov);
        }
            //System.out.println("\t   ..............");
    }
    
    // attaches a detector to the cell containing it; returns false if the detector is not on this corridor
    // a cell holds at most one mainline, one entrance ramp, and one exit ramp detector; a second one replaces the first, with a warning
    public boolean addDetector(Detector det){
        Cell cell = findCell(det.getLocation());
        
        // ramp detectors are on the ramp, often more than 50 ft from the mainline, so they are matched to the nearest point
        // of the corridor within MAX_RAMP_DISTANCE
        if(cell == null && det.getType() != Detector.Type.Mainline){
            cell = findNearestCell(det.getLocation(), MAX_RAMP_DISTANCE);
        }
        
        if(cell == null){
            return false;
        }
        
        // a cell can contain more than one ramp in the same direction (e.g. the two entrance ramps of a cloverleaf);
        // their flows are added
        if(det.getType() == Detector.Type.Exit){
            if(cell.hasOutflowDet()){
                det = new DetectorGroup(new Detector[]{cell.getOutflowDet(), det});
            }
            cell.addOutflowDet(det);
        }
        else if(det.getType() == Detector.Type.Merge){
            if(cell.hasInflowDet()){
                det = new DetectorGroup(new Detector[]{cell.getInflowDet(), det});
            }
            cell.addInflowDet(det);
        }
        else{
            if(cell.hasDetector()){
                System.out.println("Warning: cell "+cell.cell_idx+" already has mainline detector "+cell.getDetector()+", replaced by "+det);
            }
            cell.setDetector(det);
        }
        
        return true;
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
