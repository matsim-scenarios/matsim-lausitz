package org.matsim.run.analysis;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.PersonStuckEvent;
import org.matsim.api.core.v01.events.handler.PersonMoneyEventHandler;
import org.matsim.api.core.v01.events.handler.PersonStuckEventHandler;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.population.PersonUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import picocli.CommandLine;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;
import tech.tablesaw.columns.Column;
import tech.tablesaw.io.csv.CsvWriteOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.*;

import static org.matsim.api.core.v01.TransportMode.*;
import static org.matsim.application.ApplicationUtils.globFile;
import static org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import static org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;
import static org.matsim.core.router.TripStructureUtils.StageActivityHandling.ExcludeStageActivities;
import static org.matsim.run.analysis.HeadersKN.*;

@CommandLine.Command(name = "monetary-utility", description = "List and compare fare, dailyRefund and utility values for agents in base and policy case.")
public class AgentWiseComparisonKN implements MATSimAppCommand {
	private static final Logger log = LogManager.getLogger( AgentWiseComparisonKN.class );
	public static final String KN_MONEY = "knMoney";


	@CommandLine.Parameters(arity = "1..*", description = "Path to run output directories for which analysis should be performed.")
	private List<Path> inputPaths;

	@CommandLine.Option(names = "--base-path", description = "Path to run directory of base case.", required = true)
	private Path baseCasePath;

	@CommandLine.Option(names = "--prefix", description = "Prefix for filtered events output file, optional. This can be a list of multiple prefixes. " +
		"Number of prefixes has to be equal to number of inputPaths and the list of prefixes has to have the same order as inputPaths.", split = ",")
	private List<String> prefixList = new ArrayList<>();
	// (yy not totally obvious to me how this works.  Different prefixes for different input paths? kai, nov'25)

	NumberFormat format = NumberFormat.getNumberInstance( Locale.GERMANY);


	public static void main(String[] args) {
		new AgentWiseComparisonKN().execute(args );
	}

	@Override
	public Integer call() throws Exception {
		// yyyyyy we do not read the events from the base case so if there is important info (such as agents stuck in the base case) we ignore it!!

		format.setMaximumFractionDigits(2);
		format.setMinimumFractionDigits( 2 );

		if (!prefixList.isEmpty() && prefixList.size() != inputPaths.size()) {
			log.error("The numbers of prefixes {} and input paths {} do not match.", prefixList, inputPaths);
			return 2;
		}

		List<String> eventsFiles = new ArrayList<>();

		if (!prefixList.isEmpty()) {
			for (String prefix : prefixList) {
				eventsFiles.add("*" + prefix + "output_events_filtered.xml.gz");
			}
		} else {
			eventsFiles.add("*output_events.xml.gz");
		}

		String basePopulationFilename = globFile( baseCasePath, "*vtts_experienced_plans.xml.gz" ).toString();
		String baseConfigFilename = globFile( baseCasePath, "*output_config_reduced.xml" ).toString();
		// (The reduced config has fewer problems with newly introduced config params.)

		Config config = ConfigUtils.loadConfig(baseConfigFilename);
		config.controller().setOverwriteFileSetting( OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles );

		config.scoring().addActivityParams( new ActivityParams( TripStructureUtils.createStageActivityType( car ) ).setScoringThisActivityAtAll( false ) );
		config.scoring().addActivityParams( new ActivityParams( TripStructureUtils.createStageActivityType( bike ) ).setScoringThisActivityAtAll( false ) );
		config.scoring().addActivityParams( new ActivityParams( TripStructureUtils.createStageActivityType( walk ) ).setScoringThisActivityAtAll( false ) );
		config.scoring().addActivityParams( new ActivityParams( TripStructureUtils.createStageActivityType( pt ) ).setScoringThisActivityAtAll( false ) );


		Population basePopulation = PopulationUtils.readPopulation( basePopulationFilename );
		log.warn( "popSize={}",basePopulation.getPersons().size());
		cleanPopulation( basePopulation );
		log.warn( "popSize={}",basePopulation.getPersons().size());

		for (String pattern : eventsFiles) {
			handleEventsfile( baseCasePath, pattern, basePopulation );
		}

		// ###

		Table baseTable = generateTableFromPopulation( basePopulation, config );

		for( Column<?> column : baseTable.columns() ){
			if ( column instanceof DoubleColumn ) {
				((DoubleColumn) column).setPrintFormatter( format, "n/a" );
			}
		}

		final Table sortedTable = baseTable.sortDescendingOn( SCORE );
		log.info("print sortedTable:");
		System.out.println( sortedTable );

		{
			Table carTable = baseTable.where( baseTable.stringColumn( MODE_SEQ ).containsString( "car" ) );
			log.info( "print carTable:");
			System.out.println( carTable );
		}
		if (eventsFiles.size() == 1) { // maybe this special case in included in the next?
			String pattern = eventsFiles.getFirst();
			for (Path inputPath : inputPaths) {
				readPolicyDataAndCompare(inputPath, pattern, baseTable );
			}
		} else {
			for (String pattern : eventsFiles) {
				readPolicyDataAndCompare( inputPaths.get(eventsFiles.indexOf(pattern ) ), pattern, baseTable );
			}
		}
		return 0;
	}
	private static void handleEventsfile( Path baseCasePath, String pattern, Population population ){
		String baseEventsFile = globFile( baseCasePath, pattern ).toString();

		double popSizeBefore = population.getPersons().size();

		EventsManager events = EventsUtils.createEventsManager();
		events.addHandler( new MyMoneyEventsHandler( population ) );
		events.addHandler( new MyStuckEventsHandler( population ) );
		events.initProcessing();

		MatsimEventsReader baseReader = new MatsimEventsReader( events );
		baseReader.readFile( baseEventsFile );
		events.finishProcessing();

		log.warn("popSize before={}; popSize after={}; ", popSizeBefore, population.getPersons().size() );

	}
	@NotNull private static Table generateTableFromPopulation( Population population, Config config ){

		Table table = Table.create( StringColumn.create( PERSON_ID)
			, DoubleColumn.create( INCOME)
			, DoubleColumn.create( SCORE)
			, DoubleColumn.create( TTIME)
			, DoubleColumn.create( WEIGHTED_TTIME)
			, DoubleColumn.create( MONEY)
//			, DoubleColumn.create( HeadersKN.WEIGHTED_MONEY)
			, DoubleColumn.create( ASCS)
			, StringColumn.create( MODE_SEQ)
			, StringColumn.create( ACT_SEQ)
								  );

		for( Person person : population.getPersons().values() ){
			table.stringColumn( PERSON_ID).append( person.getId().toString() );
			table.doubleColumn( INCOME).append( PersonUtils.getIncome( person ) );
		}
		double avIncome = table.doubleColumn( INCOME).mean();
		log.warn("averageIncome={}", avIncome );
		table.addColumns( table.doubleColumn( INCOME).reciprocal().multiply( avIncome / config.scoring().getMarginalUtilityOfMoney() ).setName( UTL_OF_MONEY) );

		MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();
		for( Person person : population.getPersons().values() ){

			// the following two lines are very slow; presumably, we better do full column operations and add the monetized columns separately.
//			int row = table.stringColumn( HeadersKN.personId ).isEqualTo( person.getId().toString() ).iterator().nextInt();
//			double margUtlOfMoney = table.doubleColumn( HeadersKN.utlOfMoney ).get( row );

			table.doubleColumn( SCORE).append( person.getSelectedPlan().getScore() );

			double sumTtime = 0.;
			double sumMoney = 0.;
			Double moneyFromEvents = (Double) person.getAttributes().getAttribute( KN_MONEY );
			if ( moneyFromEvents!=null ) {
				sumMoney += moneyFromEvents ;
			};
			Map<String,Double> dailyMoneyByMode = new TreeMap<>();

			double sumWeightedTtime = 0.;
			double sumAscs = 0.;
			for( TripStructureUtils.Trip trip : TripStructureUtils.getTrips( person.getSelectedPlan() ) ){
				double tripTtime = 0.;
				for( Leg leg : trip.getLegsOnly() ){
					// ttime:
					sumTtime += leg.getTravelTime().seconds();
					tripTtime += leg.getTravelTime().seconds();

					// money:
					final ModeParams modeParams = config.scoring().getModes().get( leg.getMode() );
					sumMoney += leg.getRoute().getDistance() * modeParams.getMonetaryDistanceRate();

					dailyMoneyByMode.put( leg.getMode(), modeParams.getDailyMonetaryConstant() );
					// we only want this once!

					// ascs:
					sumAscs += modeParams.getConstant() ;
				}
				Double mutts_h = getMUTTS_h( trip.getDestinationActivity() );
				if ( mutts_h != null ) {
					sumWeightedTtime += mutts_h * tripTtime / 3600.;
				} else {
					throw new RuntimeException("find default value");
				}
			}


			// ttime:
			table.doubleColumn( TTIME ).append( sumTtime/3600  );
			table.doubleColumn( HeadersKN.WEIGHTED_TTIME ).append( -sumWeightedTtime );

			// money:
			double dailyMoney = 0.;
			for( Double value : dailyMoneyByMode.values() ){
				dailyMoney += value;
			}
			table.doubleColumn( MONEY).append( sumMoney + dailyMoney );

			// ascs:
			table.doubleColumn( ASCS).append( sumAscs );

			List<String> modes = new ArrayList<>();
			for( TripStructureUtils.Trip trip : TripStructureUtils.getTrips( person.getSelectedPlan() ) ){
				mainModeIdentifier.identifyMainMode( trip.getTripElements() );
				modes.add( shortenModeString( mainModeIdentifier.identifyMainMode( trip.getTripElements() ) ) );
			}
			final String modeString = String.join( "--", modes );
			table.stringColumn( MODE_SEQ).append( modeString ) ;
			if ( isTestPerson( person.getId() ) ) {
				log.warn("personId={}, modes={}", person.getId(), modeString );
			}
			List<String> acts = new ArrayList<>();
			double lastActEndTime = 0.;
			for( Activity activity : TripStructureUtils.getActivities( person.getSelectedPlan(), ExcludeStageActivities ) ){
					acts.add( activity.getType().substring( 0, Math.min( 4, activity.getType().length()) ) );
					if ( isTestPerson( person.getId() ) ) {
						// (note that the first act typically has no start time and the last no end time.)
						if ( activity.getStartTime().isUndefined() ) {
							lastActEndTime = activity.getEndTime().seconds();
							log.warn("===");
							log.warn( "personId={}; activity type={}; actStartTime={}; actEndTime={}", person.getId(), activity.getType(), activity.getStartTime(), activity.getEndTime().seconds() );
						} else if ( activity.getEndTime().isUndefined() ) {
							log.warn( "personId={}; activity type={}; duration={}", person.getId(), activity.getType(), (lastActEndTime - activity.getStartTime().seconds()) + 24*3600 );
						} else {
							log.warn( "personId={}; activity type={}; " +
//											  "actStartTime={}; actEndTime={};" +
											  " duration={}", person.getId(), activity.getType()
//									, activity.getStartTime().seconds()/3600., activity.getEndTime().seconds()/3600
									, (activity.getEndTime().seconds() - activity.getStartTime().seconds()));
						}
					}
			}
			table.stringColumn( ACT_SEQ).append( String.join( "|", acts ) );
		}

		table.addColumns( table.doubleColumn( SCORE ).divide( table.doubleColumn( UTL_OF_MONEY ) ).setName( BENEFIT ) ) ;

		table.addColumns( table.doubleColumn( MONEY ).multiply( table.doubleColumn( UTL_OF_MONEY ) ).setName( WEIGHTED_MONEY ) );

		return table;
	}

	private void readPolicyDataAndCompare( Path inputPath, String pattern, Table tableBase ) throws IOException {
		log.info("Running on {}", inputPath);

		String baseConfigFilename = globFile( inputPath, "*output_config.xml" ).toString();
		Config config = ConfigUtils.loadConfig(baseConfigFilename);

		String populationFileName = globFile(inputPath, "*vtts_experienced_plans.xml.gz").toString();

		Population policyPopulation = PopulationUtils.readPopulation( populationFileName );
		log.warn( "popSize={}",policyPopulation.getPersons().size());
		cleanPopulation( policyPopulation );
		log.warn( "popSize={}",policyPopulation.getPersons().size());

		handleEventsfile( inputPath, pattern, policyPopulation );

		Table tablePolicy = generateTableFromPopulation( policyPopulation, config );

		log.info("print unsorted policy table:");
		System.out.println( tablePolicy );

		// Compute overlapping columns (excluding join keys)
		Set<String> leftCols = new HashSet<>(tableBase.columnNames());
		Set<String> rightCols = new HashSet<>(tablePolicy.columnNames());

		leftCols.remove( PERSON_ID);
		rightCols.remove( PERSON_ID);

		Set<String> duplicates = new HashSet<>(leftCols);
		duplicates.retainAll(rightCols);

		// Rename duplicates in right-hand table
		for (String dup : duplicates) {
			tablePolicy.column( dup ).setName( keyTwoOf(  dup ) );
		}
//		Table filteredTableBase = tableBase.where( tableBase.stringColumn( MODE_SEQ ).containsString("car").andNot( tableBase.stringColumn( MODE_SEQ ).containsString( "eCar" ) ) );
		Table filteredTableBase = tableBase;

//		Table filteredTablePolicy = tablePolicy.where( tablePolicy.stringColumn( keyTwoOf(HeadersKN.MODE_SEQ) ).containsString( "drt" ) );
//		Table filteredTablePolicy = tablePolicy.where( tablePolicy.stringColumn( HeadersKN.keyTwoOf(HeadersKN.MODE_SEQ ) ).containsString( "eCar" ) );
		Table filteredTablePolicy = tablePolicy;

		Table joinedTable = filteredTableBase.joinOn( PERSON_ID).inner( filteredTablePolicy );

		Table deltaTable = Table.create( joinedTable.column( PERSON_ID)
			, joinedTable.column( UTL_OF_MONEY)
			, joinedTable.column( SCORE )
			, joinedTable.column( keyTwoOf( SCORE ) )
			, joinedTable.column( TTIME )
			, deltaColumn( joinedTable, TTIME)
			, joinedTable.column( WEIGHTED_TTIME )
			, joinedTable.column( WEIGHTED_MONEY )
			, joinedTable.column( ASCS )
			, deltaColumn( joinedTable, SCORE)
			, deltaColumn( joinedTable, WEIGHTED_TTIME )
			, deltaColumn( joinedTable, WEIGHTED_MONEY)
			, deltaColumn( joinedTable, ASCS)
			, joinedTable.column( MODE_SEQ)
			, joinedTable.column( keyTwoOf( MODE_SEQ) )
//				, joinedTable.column( HeadersKN.actSeq )
//				, joinedTable.column( keyTwoOf( HeadersKN.actSeq ) )
//				, joinedTable.column( HeadersKN.STUCK )
									   );

		deltaTable.write().usingOptions( CsvWriteOptions.builder( "deltaTable.tsv" ).separator( '\t' ).build() );

		log.warn("###");
		log.warn(
			"D_SCORE_MEAN=" + deltaTable.doubleColumn( deltaOf( SCORE ) ).mean()
				+"; d_w_ttime_mean=" + deltaTable.doubleColumn( deltaOf( WEIGHTED_TTIME ) ).mean()
				+ "; d_w_money=" + deltaTable.doubleColumn( deltaOf( WEIGHTED_MONEY) ).mean()
				+ "; d_w_ascs=" + deltaTable.doubleColumn( deltaOf( ASCS ) ).mean()
				+ "; d_ttime_mean=" + deltaTable.doubleColumn( deltaOf( TTIME) ).mean() / 3600 * 6
				);
		log.warn("###");

		Table sortedTable = deltaTable.sortOn( deltaOf( SCORE) );

		// I can set the format to columns that already exist at this stage:
		for( Column<?> column : sortedTable.columns() ){
			if ( column instanceof DoubleColumn ) {
				((DoubleColumn) column).setPrintFormatter( format, "n/a" );
			}
		}

		log.info( "sorted table coming here ..." );
		System.out.println( sortedTable );
//		throw new RuntimeException("We do not want to continue here, aborting!");

	}
	private DoubleColumn deltaColumn( Table joinedTable, String key ){
		return joinedTable.doubleColumn( keyTwoOf( key ) ).subtract( joinedTable.doubleColumn( key ) ).setName( deltaOf( key ) );
	}

	private static boolean isTestPerson( Id<Person> personId ){
		return switch( personId.toString() ){
			case "766222", "459926", "1279437", "1055071", "1083364", "1450752", "114301" , "203311",
				 // lausitz:
				 "1012515",
					// dresden:
					"1084690","34371","843219","588488",
				// berlin:
				 "berlin_c5d528ea"
					-> true;
			default -> false;
		};
	}

	private static void cleanPopulation( Population basePopulation ){
		List<Id<Person>> personsToRemove = new ArrayList<>();
		for( Person person : basePopulation.getPersons().values() ){
			Id<Person> personId = person.getId();
//			if ( personId.toString().contains("goods") || personId.toString().contains("commercial") || personId.toString().contains("freight")  ) {
			if ( !"person".equals( PopulationUtils.getSubpopulation( person ) ) ) {
				personsToRemove.add( person.getId() );
			}
		}
		for( Id<Person> personId : personsToRemove ){
			basePopulation.removePerson( personId );
		}
	}

	private static String shortenModeString( String string ) {
		return string.replace( "electric_car", "eCar" ).replace( "electric_ride", "eRide" );
	}

	private static class MyMoneyEventsHandler implements PersonMoneyEventHandler{
		private final Population population;
		public MyMoneyEventsHandler( Population population ){
			this.population = population;
		}
		@Override public void handleEvent( PersonMoneyEvent event ){
			Person person = population.getPersons().get( event.getPersonId() );
			Double moneyAttrib = (Double) person.getAttributes().getAttribute( KN_MONEY );
			if ( moneyAttrib == null ) {
				person.getAttributes().putAttribute( KN_MONEY, event.getAmount() );
			} else {
				person.getAttributes().putAttribute( KN_MONEY, moneyAttrib + event.getAmount() );
			}
		}
	}

	private static class MyStuckEventsHandler implements PersonStuckEventHandler {
		private final Population population;
		public MyStuckEventsHandler( Population population ){
			this.population = population;
		}
		@Override public void handleEvent( PersonStuckEvent event ){
			Person result = population.removePerson( event.getPersonId() );
		}
	}

	// yyyyyy cannot use matsim head since lausitz is too old
	private static final String VTTS_H = "VTTS_h (incoming trip)";
	private static final String MUTTS_H = "mUTTS_h (incoming trip)";

	public static void setMUTTS_h( Activity activity, double mUTTSh ){
		activity.getAttributes().putAttribute( MUTTS_H, mUTTSh );
	}
	public static Double getMUTTS_h( Activity activity ) {
		return (Double) activity.getAttributes().getAttribute( MUTTS_H );
	}

	public static void setVTTS_h( Activity activity, double vttSh ){
		activity.getAttributes().putAttribute( VTTS_H, vttSh );
	}
	public static Double getVTTS_h( Activity activity ) {
		return (Double) activity.getAttributes().getAttribute( VTTS_H );
	}



}
