/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package umn.pw_estimation;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;
import umn.pw_estimation.PW.Link;
import org.apache.commons.math3.linear.RealMatrix;
import umn.pw_estimation.Input.FakeDetector;
import umn.pw_estimation.PW.Corridor;
import umn.pw_estimation.Input.HistoricalDetector;
import umn.pw_estimation.Input.ReadData;
import umn.pw_estimation.PW.Coordinate;
/**
 *
 * @author mlevin
 */
public class Main {

    public static void main(String[] args) throws IOException {
        
        double dt = 3;
        
        ReadData read = new ReadData();
        Map<String, Corridor> corridors = read.readOSM("610", new File("data/MN610/geometry.txt"), dt, new String[]{"WB", "EB"}, new File("data/MN610/MN610_Edited.csv"), new String[]{"T.H.610 WB", "T.H.610 EB"});
        read.readDetectorData(new File("data/MN610/detector_data.csv"));
       
        for(String direction : corridors.keySet()){
            Corridor corridor = corridors.get(direction);
            corridor.init(0);
        }
        
        for(String direction : corridors.keySet()){
            Corridor corridor = corridors.get(direction);
            PrintStream fileout = new PrintStream(new FileOutputStream(new File(corridor.getName()+" estimate.csv")), true);
            int duration = (int)(0.5*60); // in seconds
            corridor.estimate(duration, fileout); // this creates a CSV file
            fileout.close();
        }
        
        /*
        double dt = 3;
        double maxspeed = 80.8;
        
        Link test = new Link("test", 0.5, dt, 35, maxspeed, 2400, 15, 240, 1);
        test.cells[0].setDetector(new FakeDetector("test", new Coordinate(0, 0), 12, 35)); 
        test.cells[5].addInflowDet(new FakeDetector("test", new Coordinate(0, 0), 12, 35)); 
        //test.cells[5].setDetector(new FakeDetector("test", new Coordinate(0, 0), 12, 35));
        
        Corridor corridor = new Corridor(new Link[]{test}, dt);
        
        corridor.init(0);
        
        for(int t = 0; t < 5; t++){
            corridor.printCells();
        
            corridor.nextTimestep();
            
        } 
        */
       
    }
}