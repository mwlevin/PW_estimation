/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package umn.pw_estimation;

import umn.pw_estimation.PW.Link;
import org.apache.commons.math3.linear.RealMatrix;
import umn.pw_estimation.PW.Corridor;
import umn.pw_estimation.Input.LoopDetector;
/**
 *
 * @author mlevin
 */
public class Main {

    public static void main(String[] args) {

        double dt = 6;
        Link test = new Link(0.3, dt, 60, 2400, 15, 240, 1);
        test.addDetector(0, new LoopDetector("test"));
        
        Corridor corridor = new Corridor(new Link[]{test}, dt);
        
        corridor.init();
        
        for(int t = 0; t < 5; t++){
            corridor.printCells();
        
            corridor.nextTimestep();
        
        }
    }
}
