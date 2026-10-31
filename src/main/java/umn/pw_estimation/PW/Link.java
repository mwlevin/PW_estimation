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
    private double Q; // capacity per lane veh/s, before traffic breaks down
    private double Qd; // queue discharge rate per lane veh/s: the largest flow of congested traffic; Qd <= Q
    private double w; // congested wave speed m/s
    private double K; // jam density per lane veh/m
    private int numLanes;
    private double length; // units of meters
    private double maxspeed; // exceeding this violates CFL

    private String name;

    private List<Coordinate> coords;

    // length in miles; v, maxspeed and w in mph; Q and Qd in veh/hr per lane; K in veh/mi per lane
    // all values are converted to meters and seconds here
    // flow per lane is v*k up to the critical density Q/v, and min(Qd, w*(K - k)) above it (see getEqFlowPerLane())
    // with Qd = Q, this is the trapezoid min(v*k, Q, w*(K - k)) if w*(K - Q/v) >= Q, and a triangle if w = Q / (K - Q/v)
    // if w*(K - Q/v) < Qd or Qd < Q, the flow drops when traffic becomes congested (capacity drop)
    public Link(String name, double length, double v, double maxspeed, double Q, double Qd, double w, double K, int numLanes){
        this.name = name;
        this.length = length * 1609.3;  // miles to meters
        this.v = v / 2.237;  // converting units here, mph to m/s
        this.Q = Q / 3600.0;  // veh/hr to veh/s
        this.Qd = Math.min(Q, Qd) / 3600.0;
        this.w = w / 2.237;  // mph to m/s
        this.K = K / 1609.3;  // veh/mi to veh/m  // K = jam density
        this.numLanes = numLanes;
        this.maxspeed = maxspeed / 2.237;
        
        regimeArray();
        
        
    }
    
    // without a separate queue discharge rate (Qd = Q)
    public Link(String name, double length, double v, double maxspeed, double Q, double w, double K, int numLanes){
        this(name, length, v, maxspeed, Q, Q, w, K, numLanes);
    }
    
    // same as above, but the length is computed from the OpenStreetMap coordinates of the road
    public Link(String name, List<Coordinate> coords, double v, double maxspeed, double Q, double Qd, double w, double K, int numLanes){
        this(name, calcLength(coords), v, maxspeed, Q, Qd, w, K, numLanes);
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

    // distance (ft) from a latitude/longitude to the nearest point of this link, and the distance (m) along the link to that point
    // uses a flat-earth approximation, which is accurate over the short distances involved
    public double[] findNearestPosition(Coordinate location){
        double best_dist = Double.MAX_VALUE;
        double best_pos = 0;
        double ft_per_deg_lat = 364000;
        double ft_per_deg_lon = 364000 * Math.cos(Math.toRadians(location.lat));
        
        for(int i = 0; i < coords.size()-1; i++){
            Coordinate a = coords.get(i);
            Coordinate b = coords.get(i+1);
            double bx = (b.lon - a.lon) * ft_per_deg_lon, by = (b.lat - a.lat) * ft_per_deg_lat;
            double px = (location.lon - a.lon) * ft_per_deg_lon, py = (location.lat - a.lat) * ft_per_deg_lat;
            double len2 = bx * bx + by * by;
            double f = len2 > 0? Math.max(0, Math.min(1, (px * bx + py * by) / len2)) : 0;
            double dist = Math.hypot(px - f * bx, py - f * by);
            if(dist < best_dist){
                best_dist = dist;
                best_pos = (calcLength(coords, i) + f * Coordinate.dist(a, b)) * 1609.3;
            }
        }
        return new double[]{best_dist, best_pos};
    }

    // equilibrium flow per lane (veh/s) for density per lane k (veh/m)
    public double getEqFlowPerLane(double k){
        if(k <= Q / v){
            return v * k;
        }
        return Math.max(0, Math.min(Qd, w * (K - k)));
    }

    // equilibrium speed (m/s) for density per lane k_lane (veh/m)
    // equilibrium speed = flow / density, which is the free flow speed up to the critical density
    // with a capacity drop, it decreases suddenly at the critical density
    public double getEquilibriumSpeed(double k_lane){
        if(k_lane <= Q / v){
            return v;
        }
        return getEqFlowPerLane(k_lane) / k_lane;
    }

    // derivative of getEquilibriumSpeed() with respect to the density per lane
    // it is 0 in free flow, -Qd/k^2 where the flow is Qd, and -w K/k^2 on the congested branch
    // (the drop at the critical density has no derivative; the derivative on each side is used)
    public double getDerivEqSpeed(double k_lane){
        if(k_lane <= Q / v || k_lane >= K){
            return 0;
        }
        else if(w * (K - k_lane) >= Qd){
            return -Qd / (k_lane * k_lane);
        }
        else{
            return -w * K / (k_lane * k_lane);
        }
    }
    
    // largest flow per lane (veh/s) that can leave a cell at density per lane k: the capacity in free flow, and the
    // queue discharge rate in congestion, which is the largest flow on the congested branch, min(Qd, w (K - Q/v))
    public double getDemandCapPerLane(double k_lane){
        return k_lane <= Q / v? Q : getDischargeRate();
    }
    
    // largest flow per lane (veh/s) of congested traffic
    public double getDischargeRate(){
        return Math.min(Qd, w * (K - Q / v));
    }
    
    // largest flow per lane (veh/s) that can enter a cell at density per lane k: the capacity in free flow, and the
    // equilibrium flow in congestion
    public double getSupplyPerLane(double k_lane){
        return k_lane <= Q / v? Q : getEqFlowPerLane(k_lane);
    }
    
    // derivative of getSupplyPerLane() with respect to the density per lane
    public double getDerivSupplyPerLane(double k_lane){
        if(k_lane > Q / v && k_lane < K && w * (K - k_lane) < Qd){
            return -w;
        }
        return 0;
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
    
    // the speeds of regimes 5-7 were written as Q/(K - Q/w), which is the free flow speed v for a triangular flow-density
    // relationship, but is not meaningful for other shapes; they now use v directly, which gives the same regimes as before
    public void regimeArray(){
        array[1] = new TrafficRegime(v, 0);
        array[2] = new TrafficRegime(v, (0.9/2.1)*Q);
        array[3] = new TrafficRegime(v, (1.9/2.1)*Q);
        array[4] = new TrafficRegime(v, Q);
        array[5] = new TrafficRegime(v, Q);
        array[6] = new TrafficRegime((0.8/1.1)*v, (0.85/1.45)*Q);
        array[7] = new TrafficRegime((0.45/1.1)*v, (0.25/1.45)*Q);
        array[8] = new TrafficRegime(0, 0);
    }
    public TrafficRegime[] array(){
        return array;
    }
}