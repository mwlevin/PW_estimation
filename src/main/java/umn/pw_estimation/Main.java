/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package umn.pw_estimation;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import umn.pw_estimation.PW.Link;
import org.apache.commons.math3.linear.RealMatrix;
import umn.pw_estimation.Input.FakeDetector;
import umn.pw_estimation.PW.Corridor;
import umn.pw_estimation.Input.HistoricalDetector;
import umn.pw_estimation.Input.ReadData;
import umn.pw_estimation.PW.Coordinate;
import umn.pw_estimation.PW.DrawOSM;
/**
 *
 * @author mlevin
 */
public class Main {

    // HOW THIS PROGRAM WORKS
    //  1. readOSM() reads the road geometry and creates one Corridor per direction ("WB" and "EB").
    //     Each Corridor is a chain of Links (road segments from OpenStreetMap), and each Link is split into Cells.
    //     Detectors are matched to the cell that contains them.
    //  2. readDetectorData() loads the 30-second loop detector counts and speeds.
    //  3. init() sets the starting state of every cell, and estimate() runs the extended Kalman filter forward in time.
    //  4. The rest of main() prints and draws the estimate around a crash.
    //
    // Model parameters (tau, c_0, Kalman filter noise) are static fields at the top of Corridor.java, and the
    // flow-density relationship (free flow speed, capacity, jam density) is at the bottom of ReadData.java.
    // IMPORTANT: change Corridor.C_0 BEFORE calling readOSM(), because the cell lengths are computed from it there.
    //
    // Times are in seconds from the first row of detector_data.csv (9:00:00 AM for the current file, whose first
    // row is the interval ending 9:00:30).
    public static void main(String[] args) throws IOException {
        
        // time step in seconds; must be a whole number because Corridor stores the current time as a long
        // 6 s gave the lowest error in testing; the cell length grows with dt (see Link.java)
        double dt = 6;
        
        ReadData read = new ReadData();
        Map<String, Corridor> corridors = read.readOSM("610", new File("data/MN610/geometry.txt"), dt, new String[]{"WB", "EB"}, new File("data/MN610/MN610_Edited.csv"), new String[]{"T.H.610 WB", "T.H.610 EB"});
        read.readDetectorData(new File("data/MN610/detector_data.csv"));
        
        // init(0) builds the state vector and sets the starting values from the detector data at time 0
        // it must be called before estimate(), and calling it again restarts the estimate
        for(String direction : corridors.keySet()){
            Corridor corridor = corridors.get(direction);
            corridor.init(0);
        }
        
        // not used below; left over from earlier testing
        List<Corridor> list = new ArrayList<Corridor>();
        for(String s : corridors.keySet()){
            list.add(corridors.get(s));
            break;
        }
        
        

        
        for(String direction : corridors.keySet()){
            Corridor corridor = corridors.get(direction);
            PrintStream fileout = new PrintStream(new FileOutputStream(new File(corridor.getName()+" estimate.csv")), true);
            int duration = (int)(600); // in seconds
            // writes one row per time step: estimated k (veh/mi), u (mph), q (veh/hr) and regime for each cell, plus
            // detector values for cells with detectors (-1 means missing data). Densities and flows are totals across all lanes.
            // estimate() also saves every time step in memory (Corridor.records) for printCrashData() and getData() below.
            corridor.estimate(duration, fileout); // this creates a CSV file
            fileout.close();
        }
        
        // latitude, longitude of the crash; it must lie on the chosen corridor below, or findCell() returns null
        Coordinate crash_loc = new Coordinate(45.1306773, -93.2979796);
        // seconds from the start of the data; must be less than duration above, and at least timestep * num_timesteps
        // it is rounded down to a multiple of dt
        long crash_time = 520;
        
        Corridor wb610 = corridors.get("WB");
        Corridor eb610 = corridors.get("EB");
        
        // there must be at least this many cells upstream of the crash cell on the corridor
        int upstream_cells = 5; // number of cells shown is number of upstream_cells +1 (location of crash)
        int timestep = 60; // this is 60 seconds
        int num_timesteps = 5; // this becomes 5*timestep seconds. num_timesteps + 1 are shown (last one is time of crash)
        
        Corridor crash_corridor = wb610; // change to eb610 if needed
        
        crash_corridor.printCrashData(new File("crash_output.csv"), crash_time, crash_loc, upstream_cells, timestep, num_timesteps);
        
        // draws the corridor colored by the estimate at the crash time and saves it as map.jpg
        DrawOSM test = DrawOSM.draw(crash_corridor);
        test.setCrashLoc(crash_loc);
        test.setDrawData(crash_corridor.getData(crash_time));
        test.saveImage("map");
        
        
        /*
        // old test on a single made-up link. The Link constructor has since changed to
        // Link(name, length, dt, v, maxspeed, Q, BP, Kc, K, numLanes), so this needs updating before it will compile.
        double dt = 3;
        double maxspeed = 80.8;
        
        Link test = new Link("test", 0.2, dt, 35, maxspeed, 2400, 15, 240, 1);
        test.cells[0].setDetector(new FakeDetector("test", new Coordinate(0, 0), 12, 35)); 
        //test.cells[3].addInflowDet(new FakeDetector("test", new Coordinate(0, 0), 12, 35)); 
        //test.cells[7].addOutflowDet(new FakeDetector("test", new Coordinate(0, 0), 12, 35));
        //test.cells[5].setDetector(new FakeDetector("test", new Coordinate(0, 0), 12, 35));
        
        Corridor corridor = new Corridor("test corridor", new Link[]{test}, dt);
        
        corridor.init(0);
        
        for(int t = 0; t <= 60; t++){
            corridor.printCells();
        
            corridor.nextTimestep();
            
        }
        */
    }
}