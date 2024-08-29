package org.matsim.run;

import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.application.MATSimApplication;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.drt.estimator.DrtEstimatorModule;
import org.matsim.contrib.drt.estimator.impl.DirectTripBasedDrtEstimator;
import org.matsim.contrib.drt.estimator.impl.distribution.NormalDistributionGenerator;
import org.matsim.contrib.drt.estimator.impl.trip_estimation.ConstantRideDurationEstimator;
import org.matsim.contrib.drt.estimator.impl.waiting_time_estimation.ConstantWaitingTimeEstimator;
import org.matsim.contrib.drt.optimizer.constraints.DefaultDrtOptimizationConstraintsSet;
import org.matsim.contrib.drt.optimizer.constraints.DrtOptimizationConstraintsParams;
import org.matsim.contrib.drt.optimizer.insertion.extensive.ExtensiveInsertionSearchParams;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.SubtourModeChoiceConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleCapacity;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import picocli.CommandLine;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Run the Lausitz scenario including a regional DRT service.
 * All necessary configs will be made in this class.
 */
public final class RunLausitzDrtScenario extends MATSimApplication {
//	TODO: upload correct service area shp file to git
	@CommandLine.Option(names = "--drt-shp", description = "Path to shp file for adding drt not network links as an allowed mode.", defaultValue = "./input/shp/lausitz.shp")
	private String drtAreaShp;

	@CommandLine.Option(names = "--typ-wt", description = "typical waiting time", defaultValue = "300")
	private double typicalWaitTime;

	@CommandLine.Option(names = "--wt-std", description = "waiting time standard deviation", defaultValue = "0.3")
	private double waitTimeStd;

	@CommandLine.Option(names = "--ride-time-alpha", description = "ride time estimator alpha", defaultValue = "1.25")
	private double rideTimeAlpha;

	@CommandLine.Option(names = "--ride-time-beta", description = "ride time estimator beta", defaultValue = "300")
	private double rideTimeBeta;

	@CommandLine.Option(names = "--ride-time-std", description = "ride duration standard deviation", defaultValue = "0.3")
	private double rideTimeStd;

	private final LausitzScenario baseScenario = new LausitzScenario();

	public RunLausitzDrtScenario() {
//		TODO: change this back to "automagic" version loading of config
//		super(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
		super("input/v1.1/lausitz-v1.1-10pct.config.xml");
	}

	public static void main(String[] args) {
		MATSimApplication.run(RunLausitzDrtScenario.class, args);
	}

	@Nullable
	@Override
	protected Config prepareConfig(Config config) {
//		apply all config changes from base scenario class
		baseScenario.prepareConfig(config);

		DvrpConfigGroup dvrpConfigGroup = ConfigUtils.addOrGetModule(config, DvrpConfigGroup.class);
		dvrpConfigGroup.networkModes = Set.of(TransportMode.drt);

		MultiModeDrtConfigGroup multiModeDrtConfigGroup = ConfigUtils.addOrGetModule(config, MultiModeDrtConfigGroup.class);

		if (multiModeDrtConfigGroup.getModalElements().isEmpty()) {
			DrtConfigGroup drtConfigGroup = new DrtConfigGroup();
			drtConfigGroup.operationalScheme = DrtConfigGroup.OperationalScheme.serviceAreaBased;
			drtConfigGroup.stopDuration = 60.;
			drtConfigGroup.drtServiceAreaShapeFile = drtAreaShp;

//			optimization params now are in its own paramSet, hence the below lines
			DrtOptimizationConstraintsParams optimizationConstraints = new DrtOptimizationConstraintsParams();
			DefaultDrtOptimizationConstraintsSet optimizationConstraintsSet = new DefaultDrtOptimizationConstraintsSet();
			optimizationConstraintsSet.maxWaitTime = 1200.;
			optimizationConstraintsSet.maxTravelTimeBeta = 1200.;
			optimizationConstraintsSet.maxTravelTimeAlpha = 1.5;
			optimizationConstraints.addParameterSet(optimizationConstraintsSet);
			drtConfigGroup.addParameterSet(optimizationConstraints);
			drtConfigGroup.addParameterSet(new ExtensiveInsertionSearchParams());
			multiModeDrtConfigGroup.addParameterSet(drtConfigGroup);
		}

		// set to drt estimate and teleport
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

		SubtourModeChoiceConfigGroup smc = ConfigUtils.addOrGetModule(config, SubtourModeChoiceConfigGroup.class);

		if (!String.join(",", smc.getModes()).contains(TransportMode.drt)) {
			String[] modes = Arrays.copyOf(smc.getModes(), smc.getModes().length + 1);
			modes[modes.length - 1] = TransportMode.drt;

			smc.setModes(modes);
		}

//		creates a drt staging activity and adds it to the scoring params
		DrtConfigs.adjustMultiModeDrtConfig(multiModeDrtConfigGroup, config.scoring(), config.routing());

		return config;
	}

	@Override
	protected void prepareScenario(Scenario scenario) {
//		apply all scenario changes from base scenario class
		baseScenario.prepareScenario(scenario);

//		drt route factory has to be added as factory for drt routes, as there were no drt routes before.
		scenario.getPopulation()
			.getFactory()
			.getRouteFactories()
			.setRouteFactory(DrtRoute.class, new DrtRouteFactory());

//		add drt as allowed mode for whole Lausitz region
		Geometry geometry = new ShpOptions(drtAreaShp, null, null).getGeometry();

//		with the estimator, drt is teleported, but we may need drt as an allowed mode for
//		separate drt post simulation
		for (Link link : scenario.getNetwork().getLinks().values()) {
			if (link.getAllowedModes().contains(TransportMode.car)) {
				boolean isInside = MGC.coord2Point(link.getFromNode().getCoord()).within(geometry) ||
					MGC.coord2Point(link.getToNode().getCoord()).within(geometry);

				if (isInside) {
					Set<String> modes = new HashSet<>();
					modes.add(TransportMode.drt);
					modes.addAll(link.getAllowedModes());
					link.setAllowedModes(modes);
				}
			}
		}
		new MultimodalNetworkCleaner(scenario.getNetwork()).run(Set.of(TransportMode.drt));

		Id<VehicleType> drtTypeId = Id.create(TransportMode.drt, VehicleType.class);

//		add drt veh type if not already existing
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
		}
	}

	@Override
	protected void prepareControler(Controler controler) {
		Config config = controler.getConfig();

//		apply all controller changes from base scenario class
		baseScenario.prepareControler(controler);

		controler.addOverridingModule(new DvrpModule());
		controler.addOverridingModule(new MultiModeDrtModule());

//		the following cannot be "experts only" (like requested from KN) because without it DRT would not work
//		here, the DynActivityEngine, PreplanningEngine + DvrpModule for each drt mode are added to the qsim components
//		this is necessary for drt / dvrp to work!
		controler.configureQSimComponents(DvrpQSimComponents.activateAllModes(ConfigUtils.addOrGetModule(controler.getConfig(), MultiModeDrtConfigGroup.class)));

		MultiModeDrtConfigGroup multiModeDrtConfigGroup = MultiModeDrtConfigGroup.get(config);
		for (DrtConfigGroup drtConfigGroup : multiModeDrtConfigGroup.getModalElements()) {
			controler.addOverridingModule(new AbstractModule() {
				@Override
				public void install() {
					DrtEstimatorModule.bindEstimator(binder(), drtConfigGroup.mode).toInstance(
						new DirectTripBasedDrtEstimator.Builder()
							.setWaitingTimeEstimator(new ConstantWaitingTimeEstimator(typicalWaitTime))
							.setWaitingTimeDistributionGenerator(new NormalDistributionGenerator(1, waitTimeStd))
							.setRideDurationEstimator(new ConstantRideDurationEstimator(rideTimeAlpha, rideTimeBeta))
							.setRideDurationDistributionGenerator(new NormalDistributionGenerator(2, rideTimeStd))
							.build()
					);
				}
			});
		}

	}
}
