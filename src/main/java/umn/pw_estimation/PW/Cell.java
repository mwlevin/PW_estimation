/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.PW;

import umn.pw_estimation.Input.Detector;

/**
 *
 * @author mlevin
 */
public class Cell {
    
    protected double speed, density, inflow, outflow;
    
    private int k_idx;
    private int v_idx;
    public int cell_idx;
    private int inflow_idx, outflow_idx;
    
    private double length; // meters
    
    // number of lanes: the length-weighted average over the links this cell covers, so it may not be a whole number
    // (e.g. 150 m of 2 lanes and 50 m of 3 lanes gives 2.25 lanes); see Corridor.buildCells()
    private double lanes;
    
    
    private Cell prev, next;
    
    private Detector detector;
    
    // this refers to a detector on an entrance or exit that observes vehicles entering or exiting
    private Detector inflow_det, outflow_det;
    
    // the per-lane flow-density relationship comes from this link (the link at the middle of the cell)
    private Link link;
    
    public Cell(double length, double lanes, Link link, Cell prev){
        this.prev = prev;
        
        if(prev != null){
            prev.next = this;
        }
        
        this.length = length;
        this.lanes = lanes;
        this.link = link;
        
        this.density = 0;
        this.speed = link.getFFSpeed();
        
        k_idx = -1;
        v_idx = -1;
        inflow_idx = -1;
        outflow_idx = -1;
    }
    
    // unmetered ramp: an entrance or exit ramp without detectors in this cell. Its net flow (veh/s, positive entering and
    // negative exiting) is a variable of the state vector, estimated by the Kalman filter from the difference between the
    // model and the mainline detectors (see Corridor.addRamp()).
    // unmetered_type is 1 for an entrance (flow >= 0), -1 for an exit (flow <= 0), 0 for both in the same cell, and
    // UNMETERED_NONE if there is no unmetered ramp
    public static final int UNMETERED_NONE = -2;
    private int unmetered_type = UNMETERED_NONE;
    private int unmetered_idx = -1;
    protected double unmetered;
    
    public boolean hasUnmeteredRamp(){
        return unmetered_type != UNMETERED_NONE;
    }
    
    public int getUnmeteredType(){
        return unmetered_type;
    }
    
    // adds an unmetered entrance (type 1) or exit (type -1)
    public void addUnmeteredRamp(int type){
        unmetered_type = (unmetered_type == UNMETERED_NONE || unmetered_type == type)? type : 0;
    }
    
    public int unmetered_idx(){
        return unmetered_idx;
    }
    
    public void set_unmetered_idx(int idx){
        unmetered_idx = idx;
    }
    
    // estimated unmetered net inflow, veh/s
    public double getUnmetered(){
        return unmetered;
    }

    // historical average of the unmetered net inflow (veh/s) by time of day, in bins of PRIOR_BIN seconds starting at
    // midnight; NaN where unknown. null if there is no historical average. See Corridor.addUnmeteredPrior().
    public static final int PRIOR_BIN = 900;
    private double[] unmetered_prior;

    public void addUnmeteredPrior(double[] profile){
        if(unmetered_prior == null){
            unmetered_prior = profile.clone();
        }
        else{
            // two unmetered ramps in the same cell: their flows add
            for(int b = 0; b < profile.length; b++){
                unmetered_prior[b] += profile[b];
            }
        }
    }

    // historical average at a time of day (seconds after midnight), linearly interpolated between the middles of the
    // bins; NaN if there is no historical average for that time
    public double getUnmeteredPrior(double time_of_day){
        if(unmetered_prior == null){
            return Double.NaN;
        }
        double pos = time_of_day / PRIOR_BIN - 0.5;
        int b = (int)Math.floor(pos);
        double frac = pos - b;
        b = Math.max(0, Math.min(b, unmetered_prior.length - 1));
        int b2 = Math.min(b + 1, unmetered_prior.length - 1);
        return unmetered_prior[b] * (1 - frac) + unmetered_prior[b2] * frac;
    }
    
    public int getNumVariables(){
        int count = 2;
        
        if(hasUnmeteredRamp()){
            count++;
        }
        
        if(inflow_det != null){
            count++;
        }
        if(outflow_det != null){
            count++;
        }
        
        return count;
    }
    
    public boolean hasDetector(){
        return detector != null;
    }
    
    public void setDetector(Detector det){
        detector = det;
    }
    
    
    
    
    public void addInflowDet(Detector det){
        this.inflow_det = det;
    }
    
    public boolean hasInflowDet(){
        return inflow_det != null;
    }
    
    public Detector getInflowDet(){
        return inflow_det;
    }
    
    public void addOutflowDet(Detector det){
        this.outflow_det = det;
    }
    
    public boolean hasOutflowDet(){
        return outflow_det != null;
    }
    
    public Detector getOutflowDet(){
        return outflow_det;
    }
    
    public CellRecord createCellRecord(long time){
        return new CellRecord(time, getFlow(), density, speed, getTrafficRegime());
    }
    
    
    public double getLength(){
        return length;
    }
    
    public Detector getDetector(){
        return detector;
    }
    
    public Link getLink(){
        return link;
    }
    
    public double getNumLanes(){
        return lanes;
    }
    
    // jam density across all lanes, veh/m
    public double getK(){
        return link.getK_perLane() * lanes;
    }
    
    public double getK_perLane(){
        return link.getK_perLane();
    }
    
    public double getQ_perLane(){
        return link.getQ_perLane();
    }
    
    public double getMaxSpeed(){
        return link.getMaxSpeed();
    }
    
    public double getFFSpeed(){
        return link.getFFSpeed();
    }
    
    // equilibrium speed for density k across all lanes (detector counts are summed across lanes)
    public double getEquilibriumSpeed(double k){
        return link.getEquilibriumSpeed(k / lanes);
    }
    
    // largest flow that can leave the cell at density k (veh/m, all lanes), veh/s: the capacity in free flow, and the
    // queue discharge rate in congestion (see Link.getDemandCapPerLane())
    public double getDemandCap(double k){
        return link.getDemandCapPerLane(k / lanes) * lanes;
    }
    
    // supply across all lanes (veh/s): the largest flow that can enter the cell at density k (veh/m, all lanes)
    // it is the capacity until the critical density, then the congested flow, which decreases to 0 at jam density
    public double getSupply(double k){
        return link.getSupplyPerLane(k / lanes) * lanes;
    }
    
    // derivative of getSupply() with respect to k; by the chain rule, the lanes cancel
    public double getDerivSupply(double k){
        return link.getDerivSupplyPerLane(k / lanes);
    }
    
    // derivative of getEquilibriumSpeed() with respect to the density across all lanes
    // the chain rule gives the derivative per lane divided by the number of lanes
    public double getDerivEqSpeed(double k){
        return link.getDerivEqSpeed(k / lanes) / lanes;
    }
    public Cell getNext(){
        return next;
    }
    
    public Cell getPrev(){
        return prev;
    }
    
    protected void setNext(Cell n){
        next = n;
    }
    
    protected void setPrev(Cell p){
        prev = p;
    }
    
    public int k_idx(){
        return k_idx;
    }
    
    public int v_idx(){
        return v_idx;
    }
    
    public int inflow_idx(){
        return inflow_idx;
    }
    
    public int outflow_idx(){
        return outflow_idx;
    }
    
    // a count of -1 means missing or bad data, which is treated as 0 flow
    public double getInflow(long t){
        if(inflow_det != null){
            return Math.max(inflow_det.getLast30sCount(t), 0)/30;
        }

        return 0;
    }

    public double getOutflow(long t){
        if(outflow_det != null){
            return Math.max(outflow_det.getLast30sCount(t), 0)/30;
        }
        return 0;
    }
    
    public void set_k_idx(int idx){
        k_idx = idx;
    }
    public void set_v_idx(int idx){
        v_idx = idx;
    }
    public void set_inflow_idx(int idx){
        inflow_idx = idx;
    }
    public void set_outflow_idx(int idx){
        outflow_idx = idx;
    }
    
    
    protected double getFlow(){
        return speed * density;
    }
    
    protected double getSpeed(){
        return speed;
    }
    
    public int getTrafficRegime(){
        /*double Q = link.getQ();
        double K = link.getK();
        double w = link.getW();*/
        
        /*double sd1 = (v_idx() - speed) * (v_idx() - speed);
        double sd2 = (v_idx() - Q/(K-(Q/w))) * (v_idx() - Q/(K-(Q/w)));
        double sd3 = (v_idx() - (0.8/1.1)*(Q/(K-(Q/w)))) * (v_idx() - (0.8/1.1)*(Q/(K-(Q/w))));
        double sd4 = (v_idx() - (0.45/1.1)*(Q/(K-(Q/w)))) * (v_idx() - (0.45/1.1)*(Q/(K-(Q/w))));
        double sd5 = (v_idx() - 0) * (v_idx() - 0);
        
        double fd1 = (getFlow() - 0) * (getFlow() - 0);
        double fd2 = (getFlow() - (0.9/2.1)*Q) * (getFlow() - (0.9/2.1)*Q);
        double fd3 = (getFlow() - (1.9/2.1)*Q) * (getFlow() - (1.9/2.1)*Q);
        double fd4 = (getFlow() - Q) * (getFlow() - Q);
        double fd5 = (getFlow() - (0.85/1.45)*Q) * (getFlow() - (0.85/1.45)*Q);
        double fd6 = (getFlow() - (0.25/1.45)*Q) * (getFlow() - (0.25/1.45)*Q);*/
        
        /*double sse1 = sd1 + fd1;
        double sse2 = sd1 + fd2;
        double sse3 = sd1 + fd3;
        double sse4 = sd1 + fd4;
        double sse5 = sd2 + fd4;
        double sse6 = sd3 + fd5;
        double sse7 = sd4 + fd6;
        double sse8 = sd5 + fd1;
        
        double min = Math.min(sse1, Math.min(sse2, Math.min(sse3, Math.min(sse4, Math.min(sse5, Math.min(sse6, Math.min(sse7, sse8)))))));
        */
        /*if(min == sse1){
        return 0;
        }
        if(min == sse2){
        return 1;
        }
        if(min == sse3){
        return 2;
        }
        if(min == sse4){
        return 3;
        }
        if(min == sse5){
        return 4;
        }
        if(min == sse6){
        return 5;
        }
        if(min == sse7){
        return 6;
        }
        if(min == sse8){
        return 7;
        }
        return -1;*/
        
        double best_sse = Double.MAX_VALUE;
        int best = -1;
        
        TrafficRegime[] array = link.array();
        
        
        for(int i = 1; i <= array.length - 1; i++){
            // regimes are defined with per-lane flow; flow and speed are scaled by capacity and free flow speed so that they are comparable
            double sse = array[i].getSSE(getFlow() / lanes, getSpeed(), link.getQ_perLane(), link.getFFSpeed());
            if(sse < best_sse){
            best_sse = sse;
            best = i;
            }
        }
        return best;
    }
}
