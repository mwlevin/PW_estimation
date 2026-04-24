/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 */

package umn.pw_estimation;

import umn.pw_estimation.PW.Link;
import org.apache.commons.math3.linear.RealMatrix;
import umn.pw_estimation.PW.Corridor;
import umn.pw_estimation.PW.LoopDetector;
/**
 *
 * @author mlevin
 */
public class Main {

    public static void main(String[] args) {

        double dt = 6;
        Link test = new Link(1, dt, 60, 2400, 15, 240);
        test.addDetector(0, new LoopDetector("test"));
        
        Corridor corridor = new Corridor(new Link[]{test}, dt);
        
        corridor.init();
        
        corridor.printCells();
        
        corridor.nextTimestep();
        
        corridor.printCells();
    }
}
