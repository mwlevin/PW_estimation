/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package umn.pw_estimation.PW;

/**
 *
 * @author mlevin
 */
public class Coordinate {
    public double lat, lon;
    
    public Coordinate(double lat, double lon){
        this.lat = lat;
        this.lon = lon;
    }
    
    public static double dist(Coordinate c1, Coordinate c2){
        double lat1 = c1.lat;
        double lon1 = c1.lon;
        double lat2 = c2.lat;
        double lon2 = c2.lon;
        
        // distance between latitudes and longitudes
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        // convert to radians
        lat1 = Math.toRadians(lat1);
        lat2 = Math.toRadians(lat2);

        // apply formulae
        double a = Math.pow(Math.sin(dLat / 2), 2) + 
                   Math.pow(Math.sin(dLon / 2), 2) * 
                   Math.cos(lat1) * 
                   Math.cos(lat2);
        double rad = 3958.8; // miles
        double c = 2 * Math.asin(Math.sqrt(a));
        return rad * c;
    }
}
