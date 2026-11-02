package umn.pw_estimation.Input;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import umn.pw_estimation.PW.Corridor;

/**
 * Input files of a freeway corridor: OpenStreetMap geometry, detector inventory, ramps, and historical ramp flows.
 * MN-610 is the default. Other corridors have a corridor.properties file made by tools/corridors/build_corridor.py, with
 * keys name, geometry, inventory, ramps, prior, labels (direction of each corridor, e.g. EB,WB) and detector_labels (the
 * text in the inventory location that marks the detectors of each direction, e.g. EB|,WB|).
 *
 * @author mlevin
 */
public class CorridorConfig {
    public String name = "610";
    public File geometry = new File("data/MN610/geometry.txt");
    public File inventory = new File("data/MN610/MN610_Edited.csv");
    public File ramps = new File("data/MN610/ramps.csv");
    public File prior = new File("data/MN610/ramp_prior.csv");
    public String[] labels = {"WB", "EB"};
    public String[] detector_labels = {"T.H.610 WB", "T.H.610 EB"};

    // MN-610 if file is null or "MN610"; otherwise the properties file, or the folder that contains corridor.properties
    public static CorridorConfig load(String file) throws IOException {
        CorridorConfig config = new CorridorConfig();
        if(file == null || file.equals("MN610")){
            return config;
        }
        File f = new File(file);
        if(f.isDirectory()){
            f = new File(f, "corridor.properties");
        }
        Properties p = new Properties();
        try(FileReader in = new FileReader(f)){
            p.load(in);
        }
        config.name = p.getProperty("name");
        config.geometry = new File(p.getProperty("geometry"));
        config.inventory = new File(p.getProperty("inventory"));
        config.ramps = p.containsKey("ramps")? new File(p.getProperty("ramps")) : null;
        config.prior = p.containsKey("prior")? new File(p.getProperty("prior")) : null;
        config.labels = p.getProperty("labels").split(",");
        config.detector_labels = p.getProperty("detector_labels").split(",");
        return config;
    }

    // the corridor of each direction, with its detectors and ramps, and the historical ramp flows if the file exists
    public Map<String, Corridor> read(ReadData read, double dt) throws IOException {
        Map<String, Corridor> corridors = read.readOSM(name, geometry, dt, labels, inventory, detector_labels, ramps);
        if(prior != null && prior.exists()){
            read.readRampPrior(prior);
        }
        return corridors;
    }
}
