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
        Map<String, Corridor> corridors = read.readOSM("610", new File("data/MN610/geometry.txt"), dt, new String[]{"WB", "EB"}, new File("data/MN610/MN610 edited.csv"), new String[]{"T.H.610 WB", "T.H.610 EB"});
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
} /* t=12.00 --  //with maxspeed 45 mph
0	0.020199166784412855	16.27531311516265
1	0.01455483670858426	13.86747741817995
2	-3.30939255865869E-4	-2.9092933579512894
3	0.024255537369006916	35.14753641216159
4	0.008453755042381994	15.645638778867989
5	8.77452762583662E-34	15.645954403218596
6	-2.2075766148281546E-25	15.645954403218596
7	1.1349442876557134E-16	99.35934922926384
8	-1.5197135394655905	-275.92024017210895
9	0.9371911105642663	14.810764242941055
10	-0.060798238595934084	-92.12669752768748
11	0.14295563417230595	41.11295924441991
12	0.008453755042380448	15.645638778868003
--
	  k_i_t=0.020199166784412855 k_in_t=0.01455483670858426   C=1620.0625 term3=-0.19513023241598004
	  k_i_t=0.01455483670858426 k_in_t=-3.30939255865869E-4   C=1620.0625 term3=-0.5362036381035382
	  k_i_t=-3.30939255865869E-4 k_in_t=0.024255537369006916   C=1620.0625 term3=0.9957997329997867
	  k_i_t=0.024255537369006916 k_in_t=0.008453755042381994   C=1620.0625 term3=-0.5309225522832921
	  k_i_t=0.008453755042381994 k_in_t=8.77452762583662E-34   C=1620.0625 term3=-0.31897779129165243
	  k_i_t=8.77452762583662E-34 k_in_t=-2.2075766148281546E-25   C=1620.0625 term3=-8.916453149454692E-24
	  k_i_t=-2.2075766148281546E-25 k_in_t=1.1349442876557134E-16   C=1620.0625 term3=4.584066292245937E-15
	  k_i_t=1.1349442876557134E-16 k_in_t=-1.5197135394655905   C=1620.0625 term3=-61.381582094863184
	  k_i_t=-1.5197135394655905 k_in_t=0.9371911105642663   C=1620.0625 term3=-8.50759348732407
	  k_i_t=0.9371911105642663 k_in_t=-0.060798238595934084   C=1620.0625 term3=-4.575409768948961
	  k_i_t=-0.060798238595934084 k_in_t=0.14295563417230595   C=1620.0625 term3=16.681259377867097
	  k_i_t=0.14295563417230595 k_in_t=0.008453755042380448   C=1620.0625 term3=-2.479153758255551
	  k_i_t=0.008453755042380448 k_in_t=0.008453755042380448   C=1620.0625 term3=0.0
0.02556727388942154
0.02556727388942154
------------------------------------------------------------------------ 


t=12.00 --  // with maxspeed 80.8 mph
0	0.011604086925308047	16.1619692575706
1	0.007389729131887946	18.27790283219169
2	0.007943418887781616	20.18257979350551
3	0.004006408417371446	17.301656283949875
4	6.61298205807314E-4	15.645929715105245
5	-5.369152327349698E-34	15.645954403218596
6	4.974328292161891E-36	15.645954403218596
7	5.6215854481481E-19	-4.705446136239383
8	-0.024523430956783693	-1.4897686924565816
9	0.004806802467775773	13.666020678936707
10	0.016424546983114192	20.91256892537016
--
	  k_i_t=0.011604086925308047 k_in_t=0.007389729131887946   C=1620.0625 term3=-0.08208219641624812
	  k_i_t=0.007389729131887946 k_in_t=0.007943418887781616   C=1620.0625 term3=0.011140867673673826
	  k_i_t=0.007943418887781616 k_in_t=0.004006408417371446   C=1620.0625 term3=-0.07887430757997717
	  k_i_t=0.004006408417371446 k_in_t=6.61298205807314E-4   C=1620.0625 term3=-0.0691437999396879
	  k_i_t=6.61298205807314E-4 k_in_t=-5.369152327349698E-34   C=1620.0625 term3=-0.014048058024512214
	  k_i_t=-5.369152327349698E-34 k_in_t=4.974328292161891E-36   C=1620.0625 term3=1.1574879550306126E-32
	  k_i_t=4.974328292161891E-36 k_in_t=5.6215854481481E-19   C=1620.0625 term3=1.2007829477418188E-17
	  k_i_t=5.6215854481481E-19 k_in_t=-0.024523430956783693   C=1620.0625 term3=-0.5238258492135955
	  k_i_t=-0.024523430956783693 k_in_t=0.004806802467775773   C=1620.0625 term3=0.7874185772558381
	  k_i_t=0.004806802467775773 k_in_t=0.016424546983114192   C=1620.0625 term3=0.2386000369284259
	  k_i_t=0.016424546983114192 k_in_t=0.016424546983114192   C=1620.0625 term3=0.0
0.02556727388942154
0.02556727388942154
------------------------------------------------------------------------ */