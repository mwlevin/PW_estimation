/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package umn.pw_estimation;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import umn.pw_estimation.PW.Link;
import org.apache.commons.math3.linear.RealMatrix;
import umn.pw_estimation.PW.Corridor;
import umn.pw_estimation.Input.LoopDetector;
import umn.pw_estimation.PW.Coordinate;
/**
 *
 * @author mlevin
 */
public class Main {

    public static void main(String[] args) throws IOException {
        
        /*
        Map<String, Corridor> corridors = Corridor.readOSM(new File("data/MN610/geometry.txt"), 6, new String[]{"westbound", "eastbound"}, new File("data/MN610/detectors.csv"), new String[]{"T.H.610 WB", "T.H.610 EB"});
        */
        
        
        double dt = 6;
        Link test = new Link("test", 0.3, dt, 60, 2400, 15, 240, 1);
        test.cells[0].setDetector(new LoopDetector("test", new Coordinate(0, 0)));
        
        Corridor corridor = new Corridor(new Link[]{test}, dt);
        
        corridor.init();
        
        for(int t = 0; t < 5; t++){
            corridor.printCells();
        
            corridor.nextTimestep();
        
        }
       
    }
}
