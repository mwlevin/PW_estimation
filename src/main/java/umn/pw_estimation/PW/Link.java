/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.PW;

import umn.pw_estimation.Input.Detector;
import java.util.List;

/**
 *
 * @author mlevin
 */
public class Link implements Comparable<Link> {
    
    private static Coordinate base = new Coordinate(0, 0);

    // cell length as a multiple of the shortest length allowed by the CFL condition (see the constructor)
    public static double CELL_LENGTH_FACTOR = 1.5;
    
    public Cell[] cells;
    
    
    private double v; // free flow speed m/s
    private double Q; // capacity per lane veh/s
    private double w; // congested wave speed m/s
    private double K; // jam density per lane veh/m
    private double BP; // HCM breakpoint per lane veh/s: speed is the free flow speed for flows below BP
    private double Kc; // density at capacity per lane veh/m
    private int numLanes;
    private double length; // units of meters
    private double cell_len;
    private double maxspeed; // exceeding this violates CFL

    private String name;

    private List<Coordinate> coords;

    // dt in sec; length in miles; v and maxspeed in mph; Q and BP in veh/hr per lane; Kc and K in veh/mi per lane
    // all values are converted to meters and seconds here
    // the flow-density relationship follows the HCM speed-flow curve for basic freeway segments up to capacity (density Kc),
    // then decreases linearly to 0 at jam density K, so w = Q/(K - Kc)
    public Link(String name, double length, double dt, double v, double maxspeed, double Q, double BP, double Kc, double K, int numLanes){
        this.name = name;
        this.length = length * 1609.3;  // miles to meters
        this.v = v / 2.237;  // converting units here, mph to m/s
        this.Q = Q / 3600.0;  // veh/hr to veh/s
        this.BP = BP / 3600.0;  // veh/hr to veh/s
        this.Kc = Kc / 1609.3;  // veh/mi to veh/m
        this.K = K / 1609.3;  // veh/mi to veh/m  // K = jam density
        this.w = this.Q / (this.K - this.Kc);
        this.numLanes = numLanes;
        this.maxspeed = maxspeed / 2.237;
        
        // CFL condition for the Payne-Whitham model: the fastest wave travels at speed + c_0, so it cannot cross more than one cell per time step
        // cells are CELL_LENGTH_FACTOR times longer than this minimum, because the PW discretization is only stable in free flow when
        // (speed + c_0) * dt / dx is below about 0.7 (von Neumann stability analysis); at the minimum length, small errors grow into false jams
        double dx = CELL_LENGTH_FACTOR * (maxspeed / 2.237 + Corridor.C_0) * dt;  // converts mph to m/s
        
        int numcells = (int)Math.max(1, Math.floor(this.length/dx));
        
        cells = new Cell[numcells];
        
        cell_len = Math.max(this.length / numcells, dx);

        Cell prev = null;
        for(int i = 0; i < cells.length; i++){
            cells[i] = new Cell(cell_len, this, prev);
            prev = cells[i];
        }
        
        regimeArray();
        
        
    }
    
    // same as above, but the length is computed from the OpenStreetMap coordinates of the road
    public Link(String name, List<Coordinate> coords, double dt, double v, double maxspeed, double Q, double BP, double Kc, double K, int numLanes){
        this(name, calcLength(coords), dt, v, maxspeed, Q, BP, Kc, K, numLanes);
        this.coords = coords;
        
        // I don't care what this is! I just need any reference point
        base = coords.get(0); 
    }
    
    // compares links by distance from a reference point; not used to sort links (see Corridor.sortLinks())
    public int compareTo(Link rhs){
        double distToBase = Coordinate.dist(coords.get(0), base)*1609.3;
        double rhsDistToBase = Coordinate.dist(rhs.coords.get(0), base)*1609.3;
        
        if(distToBase > rhsDistToBase){
            return 1;
        }
        else if(distToBase < rhsDistToBase){
            return -1;
        }
        else{
            return 0;
        }
    }
    
    public List<Coordinate> getCoordinates(){
        return coords;
    }
    
    public Coordinate getFirstCoordinate(){
        return coords.get(0);
    }
    
    public Coordinate getLastCoordinate(){
        return coords.get(coords.size()-1);
    }
    
    public double getMaxSpeed(){
        return maxspeed;
    }
    
    public double getLength(){
        return length;
    }
    
    // finds the cell containing a latitude/longitude, or null if it is not on this link
    // a point is on a straight piece of the road if going through it adds at most 50 ft to the distance between the piece's endpoints
    public Cell findCell(Coordinate location){
        for(int i = 0; i < coords.size()-1; i++){
            // detector should be almost in a straight line on the road
            
            double dist1 = Coordinate.dist(coords.get(i), location);
            double dist2 = Coordinate.dist(location, coords.get(i+1));
            double total_dist = Coordinate.dist(coords.get(i), coords.get(i+1));
            
            //System.out.println("\ttrying to add "+det+" "+name+" "+dist1+" "+dist2+" "+total_dist+" "+(5280*(dist1+dist2-total_dist)));
            
            // admit some error for curvature, road coordinate differences, etc.
            // if dist1 + dist2 < total_dist means it is outside of the link
            if(Math.abs((dist1 + dist2 - total_dist)*5280) <= 50){
                // now need to compute distance along road
                // distance is in miles so convert to meters
                
                
                return findCell((dist1 + calcLength(coords, i))*1609.3);
            }
        }
        
        return null;
    }
    
   
    public boolean addDetector(Detector det){

        Coordinate location = det.getLocation();
        
        Cell cell = findCell(det.getLocation());
       
        if(cell != null){
            
            if(det.getType() == Detector.Type.Exit){
                cell.addOutflowDet(det);
            }
            else if(det.getType() == Detector.Type.Merge){
                cell.addInflowDet(det);
            }
            else{
                cell.setDetector(det);
            }

            // returns true if the detector was added to this link
            return true;
        }
        else{
            return false;
        }
    }

    public Cell findCell(double position){
        assert(position > 0 && position < length);

        // cell j covers positions [j*cell_len, (j+1)*cell_len)
        int cell_idx = (int)Math.min(cells.length-1, Math.floor(position / cell_len));
        
        return cells[cell_idx];
    }
             

    
    // HCM speed-flow curve for basic freeway segments (HCM 6th edition, equation 12-1), per lane, flow q in veh/s
    private double getHCMSpeed(double q){
        if(q <= BP){
            return v;
        }
        double frac = (q - BP) / (Q - BP);
        return v - (v - Q/Kc) * frac * frac;
    }

    // derivative of getHCMSpeed() with respect to q
    private double getDerivHCMSpeed(double q){
        if(q <= BP){
            return 0;
        }
        return -(v - Q/Kc) * 2 * (q - BP) / ((Q - BP) * (Q - BP));
    }

    // equilibrium flow per lane (veh/s) for density per lane k (veh/m)
    public double getEqFlowPerLane(double k){
        if(k <= BP / v){
            return v * k;
        }
        else if(k <= Kc){
            // invert k = q / S(q) for BP <= q <= Q; q / S(q) increases with q, so use bisection
            double lo = BP;
            double hi = Q;
            for(int iter = 0; iter < 50; iter++){
                double mid = (lo + hi) / 2;
                if(mid / getHCMSpeed(mid) < k){
                    lo = mid;
                }
                else{
                    hi = mid;
                }
            }
            return (lo + hi) / 2;
        }
        else{
            return Math.max(0, w * (K - k));
        }
    }

    // derivative of getEqFlowPerLane() with respect to k
    private double getDerivEqFlowPerLane(double k){
        if(k <= BP / v){
            return v;
        }
        else if(k <= Kc){
            // k = q/S(q), so dk/dq = (S - q S')/S^2 and dq/dk = 1/(dk/dq)
            double q = getEqFlowPerLane(k);
            double S = getHCMSpeed(q);
            return S * S / (S - q * getDerivHCMSpeed(q));
        }
        else if(k < K){
            return -w;
        }
        else{
            return 0;
        }
    }

    // k is the density across all lanes (detector counts are summed across lanes)
    public double getEquilibriumSpeed(double k){
        double k_lane = k / numLanes;

        if(k_lane <= BP / v){
            return v;
        }
        return getEqFlowPerLane(k_lane) / k_lane;
    }

    // derivative of getEquilibriumSpeed() with respect to the density across all lanes
    public double getDerivEqSpeed(double k){
        double k_lane = k / numLanes;

        if(k_lane <= BP / v){
            return 0;
        }
        double q = getEqFlowPerLane(k_lane);
        return (getDerivEqFlowPerLane(k_lane) * k_lane - q) / (k_lane * k_lane) / numLanes;
    }

    public double getCapacityDensity_perLane(){
        return Kc;
    }
    
    public double getW(){
        return w;
    }
    
    public double getQ_perLane(){
        return Q;
    }
    
    public double getK_perLane(){
        return K;
    }
    
    public double getQ(){
        return Q * numLanes;
    }
    
    public double getK(){
        return K * numLanes;
    }
    
    public int getNumLanes(){
        return numLanes;
    }
    
    public double getFFSpeed(){
        return v;
    }
    
    
    public static double calcLength(List<Coordinate> coords){
        return calcLength(coords, coords.size()-1);
    }
    
    public static double calcLength(List<Coordinate> coords, int stop_idx){
        double total = 0;
        
        for(int i = 0; i < stop_idx; i++){
            total += Coordinate.dist(coords.get(i), coords.get(i+1));
        }
        
        return total;
    }
    
    public void addNextLink(Link l){
        // connect the cells 
        l.cells[0].setPrev(cells[cells.length-1]);
        cells[cells.length-1].setNext(l.cells[0]);
    }
    
    public int size(){
        return cells.length;
    }
    
    public String toString(){
        return ""+name;
    }
    
    // reference (density, flow) points for traffic regimes 1-8, used by Cell.getTrafficRegime() to label each cell's state
    // index 0 is not used
    TrafficRegime[] array = new TrafficRegime[9];
    
    public void regimeArray(){
        array[1] = new TrafficRegime(v, 0);
        array[2] = new TrafficRegime(v, (0.9/2.1)*Q);
        array[3] = new TrafficRegime(v, (1.9/2.1)*Q);
        array[4] = new TrafficRegime(v, Q);
        array[5] = new TrafficRegime(Q/(K-(Q/w)), Q);
        array[6] = new TrafficRegime((0.8/1.1)*(Q/(K-(Q/w))), (0.85/1.45)*Q);
        array[7] = new TrafficRegime((0.45/1.1)*(Q/(K-(Q/w))), (0.25/1.45)*Q);
        array[8] = new TrafficRegime(0, 0);
    }
    public TrafficRegime[] array(){
        return array;
    }
}