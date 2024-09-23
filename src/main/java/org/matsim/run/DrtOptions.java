package org.matsim.run;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.drt.optimizer.constraints.DefaultDrtOptimizationConstraintsSet;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.insertion.extensive.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.utils.io.IOUtils;
import org.matsim.pt.config.TransitRouterConfigGroup;
import org.matsim.run.prepare.PrepareNetwork;
import org.matsim.run.prepare.PrepareTransitSchedule;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleCapacity;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import picocli.CommandLine;

import java.util.Set;

/**
 * This class bundles some run parameter options and functionalities connected to drt-scenarios.
 */
public class DrtOptions {
	@CommandLine.Option(names = "--drt-shp", description = "Path to shp file for adding drt not network links as an allowed mode.", defaultValue = "./drt-area/nord-bautzen-waiting-times_utm32N.shp")
	private String drtAreaShp;

	@CommandLine.Option(names = "--intermodal-shp", description = "Path to shp file for adding intermodal tags for drt to pt intermodality.", defaultValue = "./intermodal-area/pt-intermodal-areas-ruhland.shp")
	private String intermodalAreaShp;

	@CommandLine.Option(names = "--typ-wt", description = "typical waiting time", defaultValue = "300")
	protected double typicalWaitTime;

	@CommandLine.Option(names = "--wt-std", description = "waiting time standard deviation", defaultValue = "0.3")
	protected double waitTimeStd;

	@CommandLine.Option(names = "--ride-time-alpha", description = "ride time estimator alpha", defaultValue = "1.25")
	protected double rideTimeAlpha;

	@CommandLine.Option(names = "--ride-time-beta", description = "ride time estimator beta", defaultValue = "300")
	protected double rideTimeBeta;

	@CommandLine.Option(names = "--ride-time-std", description = "ride duration standard deviation", defaultValue = "0.3")
	protected double rideTimeStd;

	@CommandLine.Option(names = "--intermodal", defaultValue = "INTERMODALITY_ACTIVE", description = "enable intermodality for DRT service")
	private IntermodalityHandling intermodal;


	/**
	 * a helper method, which makes all necessary config changes to simulate drt.
	 */
	void configureDrtConfig(Config config) {
		DvrpConfigGroup dvrpConfigGroup = ConfigUtils.addOrGetModule(config, DvrpConfigGroup.class);
		dvrpConfigGroup.networkModes = Set.of(TransportMode.drt);

		MultiModeDrtConfigGroup multiModeDrtConfigGroup = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);

		if (multiModeDrtConfigGroup.getModalElements().isEmpty()) {
			DrtConfigGroup drtConfigGroup = new DrtConfigGroup();
			drtConfigGroup.operationalScheme = DrtConfigGroup.OperationalScheme.serviceAreaBased;
			drtConfigGroup.stopDuration = 60.;
			drtConfigGroup.drtServiceAreaShapeFile = getDrtAreaShp();

//			optimization params now are in its own paramSet, hence the below lines
			DrtOptimizationConstraintsParams optimizationConstraints = new DrtOptimizationConstraintsParams();
			DefaultDrtOptimizationConstraintsSet optimizationConstraintsSet = new DefaultDrtOptimizationConstraintsSet();
			optimizationConstraintsSet.maxWaitTime = 1200.;
			optimizationConstraintsSet.maxTravelTimeBeta = 1200.;
			optimizationConstraintsSet.maxTravelTimeAlpha = 1.5;
			optimizationConstraints.addParameterSet(optimizationConstraintsSet);
//			set maxwalk distance to transit search radius. Drt is feeder for Pt.
			optimizationConstraintsSet.maxWalkDistance = ConfigUtils.addOrGetModule(config, TransitRouterConfigGroup.class).getSearchRadius();
			drtConfigGroup.addParameterSet(optimizationConstraints);
			drtConfigGroup.addParameterSet(new ExtensiveInsertionSearchParams());
			multiModeDrtConfigGroup.addParameterSet(drtConfigGroup);
		}

		// set to drt estimate and teleport
//		this enables the usage of the DrtEstimator by CL
		for (DrtConfigGroup drtConfigGroup : multiModeDrtConfigGroup.getModalElements()) {
			drtConfigGroup.simulationType = DrtConfigGroup.SimulationType.estimateAndTeleport;
		}

//		this is needed for DynAgents for DVRP
		config.qsim().setSimStarttimeInterpretation(QSimConfigGroup.StarttimeInterpretation.onlyUseStarttime);

		ScoringConfigGroup scoringConfigGroup = ConfigUtils.addOrGetModule(config, ScoringConfigGroup.class);

		if (!scoringConfigGroup.getModes().containsKey(TransportMode.drt)) {
//			ASC drt = ASC pt as discussed in PHD seminar24
//			add mode params for drt if missing and set ASC + marg utility of traveling = 0
			scoringConfigGroup.addModeParams(new ScoringConfigGroup.ModeParams(TransportMode.drt)
				.setConstant(scoringConfigGroup.getModes().get(TransportMode.pt).getConstant())
				.setMarginalUtilityOfTraveling(-0.));
		}

//		creates a drt staging activity and adds it to the scoring params
		DrtConfigs.adjustMultiModeDrtConfig(multiModeDrtConfigGroup, config.scoring(), config.routing());

		if (intermodal == IntermodalityHandling.INTERMODALITY_ACTIVE) {
			SwissRailRaptorConfigGroup srrConfig = ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);
			srrConfig.setUseIntermodalAccessEgress(true);
			srrConfig.setIntermodalAccessEgressModeSelection(SwissRailRaptorConfigGroup.IntermodalAccessEgressModeSelection.CalcLeastCostModePerStop);

//			add drt as access egress mode for pt
			SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet accessEgressDrtParam = new SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet();
			accessEgressDrtParam.setMode(TransportMode.drt);
			// Euclidean distance from Hoyerswerda to Ruhland: 20-30 km
			accessEgressDrtParam.setInitialSearchRadius(40000);
			accessEgressDrtParam.setMaxRadius(40000);
			accessEgressDrtParam.setSearchExtensionRadius(1000);
			accessEgressDrtParam.setStopFilterAttribute("allowDrtAccessEgress");
			accessEgressDrtParam.setStopFilterValue("true");
			srrConfig.addIntermodalAccessEgress(accessEgressDrtParam);

			// TODO adjust the distance after test or make it configurable
//			walk also needs to be added as access egress mode
			SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet accessEgressWalkParam = new SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet();
			accessEgressWalkParam.setMode(TransportMode.walk);
			accessEgressWalkParam.setInitialSearchRadius(1000);
			accessEgressWalkParam.setMaxRadius(1000);
			accessEgressWalkParam.setSearchExtensionRadius(0.1);
			srrConfig.addIntermodalAccessEgress(accessEgressWalkParam);

		}
	}

	/**
	 * a helper method, which makes all necessary scenario changes to simulate drt.
	 */
	void configureDrtScenario(Scenario scenario) {

		//		drt route factory has to be added as factory for drt routes, as there were no drt routes before.
		scenario.getPopulation()
			.getFactory()
			.getRouteFactories()
			.setRouteFactory(DrtRoute.class, new DrtRouteFactory());

//		prepare network for drt
//		preparation needs to be done with lausitz shp not service area shp
		PrepareNetwork.prepareDrtNetwork(scenario.getNetwork(), IOUtils.extendUrl(scenario.getConfig().getContext(), "../shp/lausitz.shp").toString());
		//		add drt veh type if not already existing
		Id<VehicleType> drtTypeId = Id.create(TransportMode.drt, VehicleType.class);
		if (!scenario.getVehicles().getVehicleTypes().containsKey(drtTypeId)) {
//			drt veh type = car veh type, but capacity 1 passenger
			VehicleType drtType = VehicleUtils.createVehicleType(drtTypeId);

			VehicleUtils.copyFromTo(scenario.getVehicles().getVehicleTypes().get(Id.create(TransportMode.car, VehicleType.class)), drtType);
			drtType.setDescription("drt vehicle copied from car vehicle type");
			VehicleCapacity capacity = drtType.getCapacity();
			capacity.setSeats(1);

			scenario.getVehicles().addVehicleType(drtType);

			Vehicle drtDummy = VehicleUtils.createVehicle(Id.createVehicleId("drtDummy"), drtType);
			drtDummy.getAttributes().putAttribute("dvrpMode", TransportMode.drt);
			drtDummy.getAttributes().putAttribute("startLink", "706048410#0");
			drtDummy.getAttributes().putAttribute("serviceBeginTime", 0.);
			drtDummy.getAttributes().putAttribute("serviceEndTime", 86400.);

			scenario.getVehicles().addVehicle(drtDummy);

//			tag intermodal pt stops for intermodality between pt and drt
			if (intermodal == IntermodalityHandling.INTERMODALITY_ACTIVE) {
				PrepareTransitSchedule.tagIntermodalStops(scenario.getTransitSchedule(), new ShpOptions(intermodalAreaShp, null, null));
			}
		}
	}

	public String getDrtAreaShp() {
		return drtAreaShp;
	}

	/**
	 * Defines if all necessary configs for intermodality between drt and pt should be made.
	 */
	enum IntermodalityHandling {INTERMODALITY_ACTIVE, INTERMODALITY_NOT_ACTIVE}

}
