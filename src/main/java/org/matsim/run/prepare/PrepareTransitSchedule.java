package org.matsim.run.prepare;

import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Scenario;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ProjectionUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import picocli.CommandLine;

@CommandLine.Command(
		name = "prepare-transit-schedule",
		description = "Tag transit stops for Intermodal trips"
)
public class PrepareTransitSchedule implements MATSimAppCommand {
	@CommandLine.Mixin
	private ShpOptions shp = new ShpOptions();

	@CommandLine.Option(names = "--input", description = "input transit schedule", required = true)
	private String input;

	@CommandLine.Option(names = "--output", description = "output path of the transit schedule", required = true)
	private String output;

	public static void main(String[] args) {
		new PrepareTransitSchedule().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Config config = ConfigUtils.createConfig();
		config.transit().setTransitScheduleFile(input);
		config.global().setCoordinateSystem("EPSG:25832");
		Scenario scenario = ScenarioUtils.loadScenario(config);
		TransitSchedule transitSchedule = scenario.getTransitSchedule();

		tagIntermodalStops(transitSchedule, shp);

		ProjectionUtils.putCRS(transitSchedule, "EPSG:25832");

		TransitScheduleWriter writer = new TransitScheduleWriter(transitSchedule);
		writer.writeFile(output);

		return 0;
	}

	/**
	 * This method does the actual tagging of the intermodal pt stops.
	 */
	public static void tagIntermodalStops(TransitSchedule transitSchedule, ShpOptions shpOptions) {
		Geometry intermodalArea = shpOptions.getGeometry();

		for (TransitStopFacility stop : transitSchedule.getFacilities().values()) {
			if (MGC.coord2Point(stop.getCoord()).within(intermodalArea)) {
				//maybe add another filter (e.g. only train station, long distance bus stop...)
				stop.getAttributes().putAttribute("allowDrtAccessEgress", "true");
			}
		}
	}
}
