/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package umn.pw_estimation;

import java.io.File;
import java.io.IOException;
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
        
        ReadData read = new ReadData();
        Map<String, Corridor> corridors = read.readOSM(new File("data/MN610/geometry.txt"), 6, new String[]{"westbound", "eastbound"}, new File("data/MN610/detectors.csv"), new String[]{"T.H.610 WB", "T.H.610 EB"});
        read.readDetectorData(new File("data/MN610/detector_data.csv"));
        
        
        double dt = 6;
        Link test = new Link("test", 0.3, dt, 60, 2400, 15, 240, 1);
        test.cells[0].setDetector(new FakeDetector("test", new Coordinate(0, 0)));
        
        Corridor corridor = new Corridor(new Link[]{test}, dt);
        
        corridor.init(0);
        
        for(int t = 0; t < 5; t++){
            corridor.printCells();
        
            corridor.nextTimestep();
        
        }
       
    }
}
