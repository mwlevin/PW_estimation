/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.PW;

import java.util.List;

/**
 * One OpenStreetMap road segment ("way"), with its geometry, number of lanes, and flow-density relationship.
 * Links do not have their own cells: Corridor divides the whole corridor into equal cells, which can cover parts of several links.
 *
 * @author mlevin
 */
public class Link implements Comparable<Link> {
    
    private static Coordinate base = new Coordinate(0, 0);
    
    private double v; // free flow speed m/s
    private double Q; // capacity per lane veh/s
    private double w; // congested wave speed m/s
    private double K; // jam density per lane veh/m
    private int numLanes;
    private double length; // units of meters
    private double maxspeed; // exceeding this violates CFL

    private String name;

    private List<Coordinate> coords;

    // length in miles; v, maxspeed and w in mph; Q in veh/hr per lane; K in veh/mi per lane
    // all values are converted to meters and seconds here
    // the flow-density relationship is trapezoidal: flow per lane is min(v*k, Q, w*(K - k)) (see getEqFlowPerLane())
    // it is triangular when w = Q / (K - Q/v), because then the free flow and congested branches meet at capacity
    public Link(String name, double length, double v, double maxspeed, double Q, double w, double K, int numLanes){
        this.name = name;
        this.length = length * 1609.3;  // miles to meters
        this.v = v / 2.237;  // converting units here, mph to m/s
        this.Q = Q / 3600.0;  // veh/hr to veh/s
        this.w = w / 2.237;  // mph to m/s
        this.K = K / 1609.3;  // veh/mi to veh/m  // K = jam density
        this.numLanes = numLanes;
        this.maxspeed = maxspeed / 2.237;
        
        regimeArray();
        
        
    }
    
    // same as above, but the length is computed from the OpenStreetMap coordinates of the road
    public Link(String name, List<Coordinate> coords, double v, double maxspeed, double Q, double w, double K, int numLanes){
        this(name, calcLength(coords), v, maxspeed, Q, w, K, numLanes);
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
    
    // distance (m) along this link to a latitude/longitude, or -1 if it is not on this link
    // a point is on a straight piece of the road if going through it adds at most 50 ft to the distance between the piece's endpoints
    public double findPosition(Coordinate location){
        for(int i = 0; i < coords.size()-1; i++){
            // detector should be almost in a straight line on the road
            
            double dist1 = Coordinate.dist(coords.get(i), location);
            double dist2 = Coordinate.dist(location, coords.get(i+1));
            double total_dist = Coordinate.dist(coords.get(i), coords.get(i+1));
            
            // admit some error for curvature, road coordinate differences, etc.
            // if dist1 + dist2 < total_dist means it is outside of the link
            if(Math.abs((dist1 + dist2 - total_dist)*5280) <= 50){
                // now need to compute distance along road
                // distance is in miles so convert to meters
                return (dist1 + calcLength(coords, i))*1609.3;
            }
        }
        
        return -1;
    }

    // trapezoidal flow-density relationship: equilibrium flow per lane (veh/s) for density per lane k (veh/m)
    public double getEqFlowPerLane(double k){
        return Math.max(0, Math.min(v * k, Math.min(Q, w * (K - k))));
    }

    // equilibrium speed (m/s) for density per lane k_lane (veh/m)
    // equilibrium speed = flow / density, which is the free flow speed until the flow reaches capacity
    public double getEquilibriumSpeed(double k_lane){
        if(k_lane <= Q / v){
            return v;
        }
        return getEqFlowPerLane(k_lane) / k_lane;
    }

    // derivative of getEquilibriumSpeed() with respect to the density per lane
    // it is 0 in free flow, -Q/k^2 at capacity, and -w K/k^2 in congestion
    public double getDerivEqSpeed(double k_lane){
        if(k_lane <= Q / v){
            return 0;
        }
        else if(k_lane <= K - Q / w){
            return -Q / (k_lane * k_lane);
        }
        else if(k_lane < K){
            return -w * K / (k_lane * k_lane);
        }
        else{
            return 0;
        }
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
    
    // number of lanes from OpenStreetMap; a cell may have a different (average) number of lanes, see Cell.getNumLanes()
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