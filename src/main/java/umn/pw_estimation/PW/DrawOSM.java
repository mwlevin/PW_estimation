package umn.pw_estimation.PW;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.WindowListener;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 *
 * @author michael
 */
public class DrawOSM extends JPanel {
    
    public static void draw(List<Corridor> corridors) throws IOException{ 
        DrawOSM test = new DrawOSM(corridors);
        
        
        
        test.setDisplayRange(0, 60);
        
        
        JFrame frame = new JFrame();
        frame.add(test);
        frame.pack();
        frame.setVisible(true);
        
        frame.addWindowListener(new WindowAdapter(){
            public void windowClosing(WindowEvent e){
                System.exit(0);
            }
        });
        
        BufferedImage image = test.createImage();
        
        String imageType = "jpg";
        String output_file = "map";
        
        File outputfile = new File(output_file+"."+imageType);

        ImageIO.write(image, imageType, outputfile);
    }
    
    public static final Color[] gradient = new Color[]{};
    
    private List<Corridor> corridors;
    
    public DrawOSM(List<Corridor> corridors){
        this.corridors = corridors;
        
        int width = 1200;
        int height = 400;
        setSize(width, height);
        setPreferredSize(new Dimension(width, height));
        
        
        minLat = Double.MAX_VALUE;
        maxLat = -Double.MAX_VALUE;
        minLon = Double.MAX_VALUE;
        maxLon = -Double.MAX_VALUE;
        
        for (Corridor corridor : corridors) {
            List<Link> links = corridor.getLinks();
            
            for (int i = 0; i < links.size(); i++) {
                Link link = links.get(i);
                
                List<Coordinate> coordinates = link.getCoordinates();
                
                for(Coordinate coord : coordinates){
                    double lon = coord.lon;
                    double lat = coord.lat;

                    minLat = Math.min(minLat, lat);
                    maxLat = Math.max(maxLat, lat);
                    minLon = Math.min(minLon, lon);
                    maxLon = Math.max(maxLon, lon);
                }
            }
        }
    }
    
    public DrawOSM(Map<String, Corridor> corridors){
        this(new ArrayList<>(corridors.values()));
    }
    
    
    
    double max_value;
    double min_value;
    
    public void setDisplayRange(double min, double max){
        this.min_value = min;
        this.max_value = max;
    }
    
    
    public Color getColor(int idx, Cell cell){
        
        return new Color((int)(255.0 * idx / cell.getLink().cells.length), 0, 0);
    }
    
    protected void paintComponent(Graphics g_){
        Graphics2D g = (Graphics2D)g_;
        
        
        
        int width = getWidth()-10;
        int height = getHeight()-10-200;
        
        
        
        g.setColor(Color.white);
        
        g.fillRect(0, 0, getWidth(), getHeight());
        
        g.setStroke(new BasicStroke(4));
        

        for(Corridor corridor : corridors){
       
            for(Link link : corridor.getLinks()){
                // initialize the coordinates
                List<Coordinate> coords = link.getCoordinates();
                
                Coordinate c1 = coords.get(0);
                Coordinate c2 = coords.get(1);
                int coord_idx = 2;
                
                Cell[] cells = link.cells;
                
                Coordinate lastDrawn = null;
                
                for(int cell_idx = 0; cell_idx < cells.length; cell_idx++){
                    
                    Cell cell = cells[cell_idx];
                    g.setColor(getColor(cell_idx, cell));
                    
                    double len_drawn = 0;
                    
                    // keep drawing until we draw the entire cell length. Avoid numerical precision error.
                    while(len_drawn < cell.getLength() - 1e-4){
                    
                        
                        // how much can we draw of the existing coordinates?
                        // we cannot draw more than the coordinate distance
                        // we also cannot draw more than the length of the cell, minus whatever we already drew
                        double coord_dist = Coordinate.dist(c1, c2)* 1609.3;
                        double travel_len = Math.min(coord_dist, cell.getLength() - len_drawn);
                        
                        // now compute the end coordinate which is travel_len/coordinate distance
                        Coordinate start = c1;
                        Coordinate end = new Coordinate(c1.lat + (c2.lat-c1.lat) * travel_len/coord_dist, c1.lon + (c2.lon-c1.lon) * travel_len/coord_dist);

                        lastDrawn = end;
                        
                        int x1 = (int) ((start.lon - minLon) / (maxLon - minLon) * width)+5;
                        int y1 = (int) ((maxLat - start.lat) / (maxLat - minLat) * height)+5;

                        int x2 = (int) ((end.lon - minLon) / (maxLon - minLon) * width)+5;
                        int y2 = (int) ((maxLat - end.lat) / (maxLat - minLat) * height)+5;

                        g.drawLine(x1, y1, x2, y2);
                        
                        // we drew travel_len distance, so update len_drawn
                        len_drawn += travel_len;
                        
                        // Do we need a new coordinate? (avoid numerical precision error)
                        if(Math.abs(coord_dist - travel_len) < 1e-4){
                            // if so, then c1 becomes are old c2, and c2 is the next coordinate from the list
                            
                            // this could cause an exception if we reach the last coordinate and try to update
                            // but if we reach the last coordinate, we don't need to update coordinates anyways
                            if(coord_idx < coords.size()){
                                c1 = c2;
                                c2 = coords.get(coord_idx++);
                            }
                        }
                        // otherwise, we want to continue drawing the next cell from the end point
                        else{
                            c1 = end;
                        }
                    }
                }
                
            }
        }

        
        
        int y = getHeight()-10-100;
        int colorheight = 20;
        
        width = getWidth()/2;
        
        int x_offset = getWidth()/4;
        
        int x = x_offset;
        
        for(int i = 0; i < gradient.length; i++){
            g.setColor(gradient[i]);
            
            int nextX = x_offset + (int)Math.round((double)(i+1)/gradient.length * width);
            
            g.fillRect(x, y, nextX-x, colorheight);
            x = nextX;
            
            if(i == 0 || i == gradient.length-1 || 
                i % (gradient.length/10) == 0    
                    ){
                double label_val = 0;
                
                if(i == gradient.length-1 ){
                    label_val = max_value;
                }
                else{
                    label_val = ((double)i / gradient.length) * (max_value - min_value) + min_value;
                }
                
                g.setColor(Color.black);
                drawRotatedString(String.format("%.1f", label_val), g, x, y+colorheight+5, Math.PI/2);
            }
        }
        
        
    }
    
    public void drawRotatedString(String text, Graphics2D g2, float textX, float textY, double angle){
        drawRotatedString(text, g2, textX, textY, angle, textX, textY);
    }
    
    public void drawRotatedString(String text,
                                        Graphics2D g2,
                                        float textX,
                                        float textY,
                                        double angle,
                                        float rotateX,
                                        float rotateY) {
       if ((text == null) || (text.equals(""))) {
           return;
       }
       AffineTransform saved = g2.getTransform();
       // apply the rotation...
       AffineTransform rotate = AffineTransform.getRotateInstance(angle, rotateX, rotateY);
       g2.transform(rotate);


           // replaces this code...
        g2.drawString(text, textX, textY);
       
       g2.setTransform(saved);
    }
    
    public BufferedImage createImage() {

        int w = this.getWidth();
        int h = this.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bi.createGraphics();
        this.paintComponent(g);
        g.dispose();
        return bi;
    }
    
    double minLat, maxLat, minLon, maxLon;
    
    
    class Node {
        double lat;
        double lon;
        
        public Node(double lat, double lon){
            this.lat = lat;
            this.lon = lon;
        }
        public String toString(){
            return "("+lat+","+lon+")";
        }
    }

    class Way {
        int id;
        List<Node> geometry;
        
        public Way(int id){
            this.id = id;
            geometry = new ArrayList<>();
        }
        
        public String toString(){
            String output = ""+id+"\n";
            for(Node n : geometry){
                output += "\t"+n+"\n";
            }
            return output;
        }
    }
    
    
}
