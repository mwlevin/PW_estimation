package umn.pw_estimation.PW;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import umn.pw_estimation.Input.Detector;

/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

/**
 * Draws one or more corridors on a map, with each cell colored by its estimated density, flow, speed, or traffic regime.
 * The map keeps the true shape of the road: one degree of longitude is drawn cos(latitude) times as wide as one degree of
 * latitude. Mainline detector stations are marked with black dots, and labels can be added at any location.
 * Use draw() to show the map in a window, or the constructor, setDrawData() and saveImage() to save it as a PNG file
 * without a window (run Java with -Djava.awt.headless=true on a computer without a display).
 *
 * @author michael
 */
public class DrawOSM extends JPanel {


    public enum DISPLAY_MODE {
        DENSITY,
        FLOW,
        SPEED,
        TRAFFIC_REGIME
    };

    public static DrawOSM draw(Corridor corridor) throws IOException{
        List<Corridor> list = new ArrayList<>();
        list.add(corridor);
        return draw(list);
    }
    public static DrawOSM draw(List<Corridor> corridors) throws IOException{
        DrawOSM test = new DrawOSM(corridors, DISPLAY_MODE.DENSITY);

        JFrame frame = new JFrame();
        frame.add(test);
        frame.pack();
        frame.setVisible(true);

        frame.addWindowListener(new WindowAdapter(){
            public void windowClosing(WindowEvent e){
                System.exit(0);
            }
        });

        return test;
    }

    // image layout, in pixels
    private static final int WIDTH = 1400;
    private static final int MARGIN = 30; // left and right of the map
    private static final int TITLE_HEIGHT = 45;
    private static final int LABEL_SPACE = 30; // above and below the map, for labels
    private static final int LEGEND_HEIGHT = 80;
    private static final int ROAD_WIDTH = 7;

    private List<Corridor> corridors;

    private Coordinate crash_loc;
    // estimate to draw for each corridor, one record per cell
    private Map<Corridor, CellRecord[]> draw_data = new HashMap<>();

    private DISPLAY_MODE display;
    private Color[] gradient;

    private String title = "";
    private List<Coordinate> label_locs = new ArrayList<>();
    private List<String> labels = new ArrayList<>();

    // map projection: x = (lon - minLon) * lon_scale, y = (maxLat - lat), both times pixels_per_degree
    private double lon_scale, pixels_per_degree;
    private int map_height;

    public DrawOSM(List<Corridor> corridors, DISPLAY_MODE display){
        this.corridors = corridors;
        this.display = display;

        minLat = Double.MAX_VALUE;
        maxLat = -Double.MAX_VALUE;
        minLon = Double.MAX_VALUE;
        maxLon = -Double.MAX_VALUE;

        for (Corridor corridor : corridors) {
            for (Link link : corridor.getLinks()) {
                for(Coordinate coord : link.getCoordinates()){
                    minLat = Math.min(minLat, coord.lat);
                    maxLat = Math.max(maxLat, coord.lat);
                    minLon = Math.min(minLon, coord.lon);
                    maxLon = Math.max(maxLon, coord.lon);
                }
            }
        }

        lon_scale = Math.cos(Math.toRadians((minLat + maxLat) / 2));
        pixels_per_degree = (WIDTH - 2 * MARGIN) / ((maxLon - minLon) * lon_scale);
        map_height = (int)Math.ceil((maxLat - minLat) * pixels_per_degree);

        int height = TITLE_HEIGHT + LABEL_SPACE + map_height + LABEL_SPACE + LEGEND_HEIGHT;
        setSize(WIDTH, height);
        setPreferredSize(new Dimension(WIDTH, height));

        switch(display){
            case DENSITY:
                gradient = Gradient.GRADIENT_TRAFFIC_DENSITY;
                setDisplayRange(0, getMaxDensity());
                break;
            case SPEED:
                gradient = Gradient.GRADIENT_TRAFFIC_SPEED;
                setDisplayRange(0, getMaxSpeed());
                break;
            case FLOW:
                gradient = Gradient.GRADIENT_TRAFFIC_DENSITY;
                setDisplayRange(0, getMaxCapacity());
                break;
            case TRAFFIC_REGIME:
                setDisplayRange(1, 8);
                break;
        }
    }

    public DrawOSM(Map<String, Corridor> corridors, DISPLAY_MODE display){
        this(new ArrayList<>(corridors.values()), display);
    }

    private double getMaxSpeed(){
        double max = 0;

        for(Corridor corridor : corridors){
            for(Cell c : corridor.getCells()){
                max = Math.max(max, c.getFFSpeed() * 2.23694);
            }
        }

        return max;
    }

    private double getMaxDensity(){
        double max = 0;

        for(Corridor corridor : corridors){
            for(Cell c : corridor.getCells()){
                max = Math.max(max, c.getK_perLane() * 1609.34);
            }
        }

        return max;
    }

    private double getMaxCapacity(){
        double max = 0;

        for(Corridor corridor : corridors){
            for(Cell c : corridor.getCells()){
                max = Math.max(max, c.getQ_perLane() * 3600);
            }
        }

        return max;
    }

    // saves the image as output_file.png
    public void saveImage(String output_file) throws IOException{
        BufferedImage image = createImage();

        String imageType = "png";

        File outputfile = new File(output_file+"."+imageType);

        ImageIO.write(image, imageType, outputfile);
    }


    double max_value;
    double min_value;

    public void setDisplayRange(double min_value, double max){
        this.min_value = min_value;
        this.max_value = max;
    }

    // estimate to draw for the first corridor (e.g. from Corridor.getData())
    public void setDrawData(CellRecord[] data){
        setDrawData(corridors.get(0), data);
    }

    public void setDrawData(Corridor corridor, CellRecord[] data){
        draw_data.put(corridor, data);
        repaint();
    }

    public void setCrashLoc(Coordinate crash){
        crash_loc = crash;
        repaint();
    }

    public void setTitle(String title){
        this.title = title;
        repaint();
    }

    // adds a text label at a location, e.g. the name of a cross street
    public void addLabel(Coordinate location, String text){
        label_locs.add(location);
        labels.add(text);
        repaint();
    }


    public static final Color[] regime_colors = new Color[]{Color.red, Color.green, Color.yellow, Color.orange, Color.blue, Color.magenta, Color.pink, Color.cyan};


    // color of a cell for its estimated state
    public Color getColor(Cell cell, CellRecord data){

        switch(display){
            case DENSITY:
                double density = data.density / cell.getNumLanes();
                return getGradientColor(density);
            case FLOW:
                double flow = data.flow / cell.getNumLanes();
                return getGradientColor(flow);
            case SPEED:
                double speed = data.speed;
                return getGradientColor(speed);
            case TRAFFIC_REGIME:
                // regimes are numbered from 1
                int traffic_regime = data.regime;
                return regime_colors[traffic_regime - 1];
        }
        return Color.white;
    }

    private Color getGradientColor(double value){
        double p = (value - min_value) / (max_value - min_value);

        return gradient[Math.max(0, Math.min(gradient.length-1, (int)Math.round(p * gradient.length)))];
    }

    private int getX(Coordinate c){
        return MARGIN + (int)Math.round((c.lon - minLon) * lon_scale * pixels_per_degree);
    }

    private int getY(Coordinate c){
        return TITLE_HEIGHT + LABEL_SPACE + (int)Math.round((maxLat - c.lat) * pixels_per_degree);
    }

    protected void paintComponent(Graphics g_){
        Graphics2D g = (Graphics2D)g_;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setColor(Color.white);
        g.fillRect(0, 0, getWidth(), getHeight());

        g.setColor(Color.black);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        g.drawString(title, MARGIN, 30);

        g.setStroke(new BasicStroke(ROAD_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        for(Corridor corridor : corridors){
            CellRecord[] data = draw_data.get(corridor);

            // cells can cover parts of several links, so walk along the coordinates of all links in driving order
            Cell[] cells = corridor.getCells();
            List<Coordinate> coords = new ArrayList<>();
            for(Link link : corridor.getLinks()){
                for(Coordinate coord : link.getCoordinates()){
                    // consecutive links share an endpoint; skip the repeat, which would be a zero-length piece
                    if(coords.isEmpty() || !coords.get(coords.size()-1).equals(coord)){
                        coords.add(coord);
                    }
                }
            }

            Coordinate c1 = coords.get(0);
            Coordinate c2 = coords.get(1);
            int coord_idx = 2;

            for(int cell_idx = 0; cell_idx < cells.length; cell_idx++){

                Cell cell = cells[cell_idx];
                g.setColor(data != null? getColor(cell, data[cell_idx]) : Color.gray);

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

                    g.drawLine(getX(start), getY(start), getX(end), getY(end));

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

            // mainline detector stations
            g.setColor(Color.black);
            for(Cell cell : cells){
                if(cell.hasDetector() && cell.getDetector().getType() == Detector.Type.Mainline){
                    Coordinate loc = cell.getDetector().getLocation();
                    g.fillOval(getX(loc) - 4, getY(loc) - 4, 8, 8);
                }
            }
        }

        g.setStroke(new BasicStroke(2));

        if(crash_loc != null){
            int x1 = getX(crash_loc);
            int y1 = getY(crash_loc);

            g.setColor(Color.blue);
            int oval_width = 16;
            g.drawOval(x1-oval_width/2, y1-oval_width/2, oval_width, oval_width);
        }

        // labels, above the road where there is room and it does not overlap an earlier label, and below otherwise
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(60, 60, 60));
        List<java.awt.Rectangle> drawn = new ArrayList<>();
        for(int i = 0; i < labels.size(); i++){
            int x = getX(label_locs.get(i));
            int y = getY(label_locs.get(i));
            int text_width = fm.stringWidth(labels.get(i));
            int text_x = Math.max(2, Math.min(getWidth() - text_width - 2, x - text_width / 2));
            java.awt.Rectangle above = new java.awt.Rectangle(text_x - 4, y - 18 - fm.getAscent(), text_width + 8, fm.getHeight());
            boolean fits_above = y - TITLE_HEIGHT > LABEL_SPACE + 10;
            for(java.awt.Rectangle r : drawn){
                fits_above = fits_above && !r.intersects(above);
            }
            if(fits_above){
                g.drawLine(x, y - 6, x, y - 14);
                g.drawString(labels.get(i), text_x, y - 18);
                drawn.add(above);
            }
            else{
                g.drawLine(x, y + 6, x, y + 14);
                g.drawString(labels.get(i), text_x, y + 14 + fm.getAscent());
                drawn.add(new java.awt.Rectangle(text_x - 4, y + 14, text_width + 8, fm.getHeight()));
            }
        }
        
        // legend
        int y = TITLE_HEIGHT + LABEL_SPACE + map_height + LABEL_SPACE + 10;
        int colorheight = 18;
        int legend_width = getWidth() / 2;
        int x_offset = getWidth() / 4;

        if(display == DISPLAY_MODE.TRAFFIC_REGIME){
            int x = x_offset;
            for(int i = 0; i < regime_colors.length; i++){
                g.setColor(regime_colors[i]);
                int nextX = x_offset + (int)Math.round((double)(i+1)/regime_colors.length * legend_width);
                g.fillRect(x, y, nextX-x, colorheight);
                g.setColor(Color.black);
                g.drawString(""+(i+1), (x+nextX)/2, y+colorheight+fm.getAscent()+2);
                x = nextX;
            }
            g.drawString("Traffic regime", x_offset - fm.stringWidth("Traffic regime") - 15, y + colorheight - 3);
        }
        else{
            for(int i = 0; i < legend_width; i++){
                g.setColor(gradient[Math.min(gradient.length - 1, i * gradient.length / legend_width)]);
                g.fillRect(x_offset + i, y, 1, colorheight);
            }
            g.setColor(Color.black);
            g.setStroke(new BasicStroke(1));
            g.drawRect(x_offset, y, legend_width, colorheight);

            // ticks at a round interval giving about 5-10 labels
            double range = max_value - min_value;
            double step = Math.pow(10, Math.floor(Math.log10(range / 5)));
            if(range / step > 20){
                step *= 5;
            }
            else if(range / step > 10){
                step *= 2;
            }
            for(double v = Math.ceil(min_value / step) * step; v <= max_value + 1e-9; v += step){
                int x = x_offset + (int)Math.round((v - min_value) / range * legend_width);
                g.drawLine(x, y + colorheight, x, y + colorheight + 5);
                String s = String.format("%.0f", v);
                g.drawString(s, x - fm.stringWidth(s) / 2, y + colorheight + 7 + fm.getAscent());
            }

            String name = "";
            switch(display){
                case DENSITY: name = "Density (veh/mi/lane)"; break;
                case SPEED: name = "Speed (mi/hr)"; break;
                case FLOW: name = "Flow (veh/hr/lane)"; break;
            }
            g.drawString(name, x_offset - fm.stringWidth(name) - 15, y + colorheight - 3);
        }
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
}
