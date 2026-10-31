package us.mn.state.dot.data.extract;

import java.io.*;
import java.net.URL;
import java.util.*;
import us.mn.state.dot.data.DataFactory;

/**
 * Headless driver for MnDOT DataExtract (datatools-4.5.5.jar).
 * Usage: HeadlessExtract <detectors.txt> <yyyy-mm-dd>[,yyyy-mm-dd...] <HH:MM start> <HH:MM end> <out.csv> [smoothing samples=1] [datasets=Speed,Volume]
 * Output layout matches the GUI's Composite File with Times as rows.
 */
public class HeadlessExtract {
	static int toSample(String hhmm) {
		String[] p = hhmm.split(":");
		return Integer.parseInt(p[0]) * 120 + Integer.parseInt(p[1]) * 2;
	}

	public static void main(String[] args) throws Exception {
		Properties props = new Properties();
		try (InputStream in = new URL("https://data.dot.state.mn.us/datatools/dataextract.properties").openStream()) {
			props.load(in);
		}
		DataFactory factory = DataFactory.create(props);
		DataRequest req = new DataRequest(factory);

		List<String> dets = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new FileReader(args[0]))) {
			String line;
			while ((line = r.readLine()) != null) {
				line = line.trim();
				if (!line.isEmpty() && !dets.contains(line)) dets.add(line);
			}
		}
		for (String d : dets) req.addSensorId(d);

		for (String ds : args[1].split(",")) {
			String[] p = ds.split("-");
			Calendar c = Calendar.getInstance();
			c.clear();
			c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
			req.addDate(c);
		}
		int smoothing = args.length > 5 ? Integer.parseInt(args[5]) : 1;
		req.addTimeRange(TimeRange.createTimeRange(toSample(args[2]), toSample(args[3]), smoothing));
		String sets = args.length > 6 ? args[6] : "Speed,Volume";
		for (String s : sets.split(",")) req.addDataSet(s.trim());

		FileFormat fmt = new CompositeFile();
		fmt.setRows(new ArrayList<>(Arrays.asList("Detectors", "Data Sets", "Dates")));
		fmt.setColumns(new ArrayList<>(Arrays.asList("Times")));
		fmt.setDataElements(new ArrayList<>(Arrays.asList("Values")));
		req.setFileFormat(fmt);
		fmt.setDataRequest(req);

		File out = new File(args[4]).getAbsoluteFile();
		Calendar first = req.getDates().iterator().next();
		try (PrintWriter pw = new PrintWriter(new FileOutputStream(out))) {
			fmt.writeFile(out.getName(), first, pw);
		}
		if (fmt.transform()) fmt.transformFile(out);
		System.out.println("Wrote " + out);
	}
}
