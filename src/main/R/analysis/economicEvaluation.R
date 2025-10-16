library(tidyverse)
library(optparse)
library(lubridate)

########################################## input params #########################################################################

option_list <- list(
  make_option(c("-r", "--runDir"), type="character", default=NULL,
              help="Path to run directory. Avoid using '\', use '/' instead.", metavar="character"),
  make_option(c("-p", "--prefix"), type = "character", default = "",
              help = "Optional prefix for aggregated cost input file (default: empty).", metavar = "character"))

opt_parser <- OptionParser(option_list=option_list)
opt <- parse_args(opt_parser)

if (is.null(opt$runDir)) {
  print_help(opt_parser)
  stop("Error: --runDir is required", call.=FALSE)
}

run_dir <- opt$runDir
run_dir_fixed <- gsub("////", "/", run_dir)
prefix <- opt$prefix
print(paste("trying to read aggregated cost csv file with prefix:", prefix))
print("If the prefix string is empty, the global file will be read.")

# if you do not want to use opt_parse, comment out the above lines starting from option_list <- ...
# you have to define run_dir_fixed yourself
# run_dir_fixed <- "C:/Users/Simon/Desktop/wd/2025-10-13"
# prefix <- "1-ruhland-bhf-pt-fare."

setwd(run_dir_fixed)
print(paste("Running analysis on run dir", getwd()))

trips_policy_path <- list.files(path=paste0(run_dir_fixed,"/analysis/analysis/"), pattern="relevant_trips_processed\\.csv\\.gz$", full.names = TRUE)
trips_base_path <- list.files(path=paste0(run_dir_fixed,"/analysis/analysis/"), pattern="relevant_base_trips_processed\\.csv\\.gz$", full.names = TRUE)
aggregated_cost_path <- list.files(path=run_dir_fixed, pattern=paste0("^", prefix, "output_aggregated_cost_comparison_to_base\\.tsv$"), full.names = TRUE)

# read trips table
trips_policy <- read.csv(file=trips_policy_path)
# read base table
trips_base <- read.csv(file=trips_base_path)
# get cost sums policy and base from file
aggregated_cost <- read.csv(aggregated_cost_path)

# only keep columns which we need for further analysis
trips_policy_reduced <- trips_policy %>%
  select(person, trip_number, trip_id, dep_time, trav_time, traveled_distance, main_mode, trav_velocity,
         traveled_distance_diff, trav_time_diff, trav_velocity_diff) %>% 
  mutate(trav_time_s = seconds(hms(trav_time)))
trips_base_reduced <- trips_base %>% 
  select(trip_id, trav_time, main_mode) %>% 
  rename("trav_time_base" = trav_time,
         "main_mode_base" = main_mode) %>% 
  mutate(trav_time_base_s = seconds(hms(trav_time_base)))
# merge tables
trips <- left_join(trips_policy_reduced, trips_base_reduced, by="trip_id")

# calc aggregated tt per mode
# the following implicitely goes with saying that the used modes / ASCs are not changed!
policy <- list(bike= list(ASC=-2.2217257149145353, mg_ut_trav_h=-4.0), car = list(ASC=0.29066953938829737, mg_ut_trav_h=0.0),
               pt = list(ASC=-2.458426826223411, mg_ut_trav_h=0.0), ride = list(ASC=-0.3899210734374805, mg_ut_trav_h=-12.0),
               walk = list(ASC=0.0, mg_ut_trav_h=0.0), drt = list(ASC=-2.458426826223411, mg_ut_trav_h=0.0))
base <- list(bike= list(ASC=-2.2217257149145353, mg_ut_trav_h=-4.0), car = list(ASC=0.29066953938829737, mg_ut_trav_h=0.0),
               pt = list(ASC=-2.458426826223411, mg_ut_trav_h=0.0), ride = list(ASC=-0.3899210734374805, mg_ut_trav_h=-12.0),
               walk = list(ASC=0.0, mg_ut_trav_h=0.0), drt = list(ASC=-2.458426826223411, mg_ut_trav_h=0.0))

# calc policy aggregated values
for (mode in names(policy)) {
  trips_mode <- trips %>% 
    filter(main_mode==mode)
  
  trip_count <- nrow(trips_mode)
  tt_sum_mode <- 0
  ut_trav_mode <- 0
  asc_sum_mode <- 0
  
  if (trip_count==0) {
    print(paste("For mode", mode, "0 trips have been filtered in policy case. Will treat this as 0s travel time."))
  } else {
    tt_sum_mode <- sum(trips_mode$trav_time_s)
    ut_trav_mode <- tt_sum_mode * (policy[[mode]][["mg_ut_trav_h"]] / 3600)
    asc_sum_mode <- trip_count * policy[[mode]][["ASC"]]
  }
  
  policy[[mode]] <- c(policy[[mode]], list(tt=tt_sum_mode, ut_trav=ut_trav_mode, asc_sum=asc_sum_mode))
}

# calc base aggregated values
for (mode in names(base)) {
  trips_mode <- trips %>%
    filter(main_mode_base==mode)

  trip_count <- nrow(trips_mode)
  tt_sum_mode <- 0
  ut_trav_mode <- 0
  asc_sum_mode <- 0

  if (trip_count==0) {
    print(paste("For mode", mode, "0 trips have been filtered in base case. Will treat this as 0s travel time."))
  } else {
    tt_sum_mode <- sum(trips_mode$trav_time_base_s)
    ut_trav_mode <- tt_sum_mode * (base[[mode]][["mg_ut_trav_h"]] / 3600)
    asc_sum_mode <- trip_count * base[[mode]][["ASC"]]
  }

  base[[mode]] <- c(base[[mode]], list(tt=tt_sum_mode, ut_trav=ut_trav_mode, asc_sum=asc_sum_mode))
}

# add all necessary values to a df
aggregated <- data.frame(
  mode=c("bike","bike","car","car","pt","pt","ride","ride","walk","walk","drt","drt"),
  case=c("base","policy","base","policy","base","policy","base","policy","base","policy","base","policy"),
  tt_aggr_s=c(base[["bike"]][["tt"]],policy[["bike"]][["tt"]],base[["car"]][["tt"]],policy[["car"]][["tt"]],
              base[["pt"]][["tt"]],policy[["pt"]][["tt"]],base[["ride"]][["tt"]],policy[["ride"]][["tt"]]
              ,base[["walk"]][["tt"]],policy[["walk"]][["tt"]],base[["drt"]][["tt"]],policy[["drt"]][["tt"]]),
  ut_trav_aggr_util=c(base[["bike"]][["ut_trav"]],policy[["bike"]][["ut_trav"]],base[["car"]][["ut_trav"]],policy[["car"]][["ut_trav"]],
              base[["pt"]][["ut_trav"]],policy[["pt"]][["ut_trav"]],base[["ride"]][["ut_trav"]],policy[["ride"]][["ut_trav"]]
              ,base[["walk"]][["ut_trav"]],policy[["walk"]][["ut_trav"]],base[["drt"]][["ut_trav"]],policy[["drt"]][["ut_trav"]]),
  asc_aggr=c(base[["bike"]][["asc_sum"]],policy[["bike"]][["asc_sum"]],base[["car"]][["asc_sum"]],policy[["car"]][["asc_sum"]],
              base[["pt"]][["asc_sum"]],policy[["pt"]][["asc_sum"]],base[["ride"]][["asc_sum"]],policy[["ride"]][["asc_sum"]]
              ,base[["walk"]][["asc_sum"]],policy[["walk"]][["asc_sum"]],base[["drt"]][["asc_sum"]],policy[["drt"]][["asc_sum"]]),
  mon_cost_aggr_eu=c(0,0,aggregated_cost$carCostBaseAggr,aggregated_cost$carCostPolicyAggr,
         aggregated_cost$subtotalFareCostBaseAggr,aggregated_cost$subtotalFareCostPolicyAggr,
         aggregated_cost$rideCostBaseAggr,aggregated_cost$rideCostPolicyAggr,0,0,
         aggregated_cost$subtotalFareCostBaseAggr,aggregated_cost$subtotalFareCostPolicyAggr)
)

if (prefix != "") {
  write_csv(aggregated, file=paste0(prefix,"aggregated_economic_evaluation.csv"))
} else {
  write_csv(aggregated, file="all-trips.aggregated_economic_evaluation.csv")
}
