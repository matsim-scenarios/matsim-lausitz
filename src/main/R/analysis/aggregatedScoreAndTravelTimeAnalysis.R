library(tidyverse)
library(optparse)
library(lubridate)

########################################## input params #########################################################################

option_list <- list(
  make_option(c("-r", "--runDir"), type="character", default=NULL,
              help="Path to run directory. Avoid using '\', use '/' instead.", metavar="character"),
  make_option(c("-b", "--baseDir"), type="character", default=NULL,
              help="Path to run directory. Avoid using '\', use '/' instead.", metavar="character")
              )

opt_parser <- OptionParser(option_list=option_list)
opt <- parse_args(opt_parser)

if (is.null(opt$runDir) || is.null(opt$baseDir)) {
  print_help(opt_parser)
  stop("Error: --runDir and --baseDir are required", call.=FALSE)
}

run_dir <- opt$runDir
run_dir_fixed <- gsub("////", "/", run_dir)
base_dir <- opt$baseDir
base_dir_fixed <- gsub("////", "/", base_dir)

# if you do not want to use opt_parse, comment out the above lines starting from option_list <- ...
# you have to define run_dir_fixed yourself
# run_dir_fixed <- "Y:/net/ils/matsim-lausitz/caseStudies/v2.0/drt-case-study/no-pooling-0-fare/output-1-ruhland-bhf_full_plans/"
# base_dir_fixed <- "Y:/net/ils/matsim-lausitz/caseStudies/v2.0/output-lausitz-v2.0-10pct-base-case-ctd_full_plans/"

setwd(run_dir_fixed)
print(paste("Running analysis on run dir", getwd()))

trips_policy_path <- list.files(path=paste0(run_dir_fixed), pattern="*output_trips.csv.gz", full.names = TRUE)
persons_policy_path <- list.files(path=paste0(run_dir_fixed), pattern="*output_persons.csv.gz", full.names = TRUE)
trips_base_path <- list.files(path=paste0(base_dir_fixed), pattern="*output_trips.csv.gz", full.names = TRUE)
persons_base_path <- list.files(path=paste0(base_dir_fixed), pattern="*output_persons.csv.gz", full.names = TRUE)

# read trips and persons tables
trips_policy <- read.csv2(file=trips_policy_path)
trips_base <- read.csv2(file=trips_base_path)
persons_policy <- read.csv2(file=persons_policy_path)
persons_base <- read.csv2(file=persons_base_path)

# only keep columns and agents which we need for further analysis
persons_policy_reduced <- persons_policy %>%
  filter(!str_detect(person, "commercialPersonTraffic|freight|goodsTraffic")) %>%
  mutate(executed_score=as.double(executed_score)) %>% 
  select(person, executed_score)
persons_base_reduced <- persons_base %>%
  filter(!str_detect(person, "commercialPersonTraffic|freight|goodsTraffic")) %>%
  mutate(executed_score=as.double(executed_score)) %>% 
  select(person, executed_score)

trips_policy_reduced <- trips_policy %>%
  filter(person %in% persons_policy_reduced$person) %>%
  select(person, trip_id, trav_time, traveled_distance, main_mode, modes) %>%
  mutate(trav_time_s = seconds(hms(trav_time)))
trips_base_reduced <- trips_base %>%
  filter(person %in% persons_base_reduced$person) %>%
  select(person, trip_id, trav_time, traveled_distance, main_mode, modes) %>%
  mutate(trav_time_s = seconds(hms(trav_time)))

# calc aggregated overall scores and tt
aggregated_score_util_policy <- sum(persons_policy_reduced$executed_score)
aggregated_score_util_base <- sum(persons_base_reduced$executed_score)
aggregated_tt_h_policy <- sum(trips_policy_reduced$trav_time_s) / 3600
aggregated_tt_h_base <- sum(trips_base_reduced$trav_time_s) / 3600

# filter for drt users only
# first filter for drt trips only (drt as main mode and as access/egress to/from pt)
trips_drt_policy <- trips_policy_reduced %>%
  filter(str_detect(modes, "drt")) %>%
  separate(trip_id, into=c("person_from_trip_id", "trip_number"), sep="_")
trips_drt_users_policy <- trips_policy_reduced %>%
  filter(person %in% trips_drt_policy$person_from_trip_id)
# get all respective trips of drt users in base case
trips_drt_users_base <- trips_base_reduced %>%
  filter(person %in% trips_drt_users_policy$person)
drt_users_policy <- persons_policy_reduced %>%
  filter(person %in% trips_drt_users_policy$person)
drt_users_base <- persons_base_reduced %>%
  filter(person %in% trips_drt_users_base$person)

if (length(trips_drt_users_policy) != length(trips_drt_users_base) ||
    length(drt_users_policy) != length(drt_users_base)) {
  stop("Number of trips of drt users and number of trips of their respective trips in the base case are not the same OR
       number of drt users and the respective agents in the base case are not the same! Aborting!")
}

# calc aggregated drt user scores and tt
aggregated_score_util_drt_users_policy <- sum(drt_users_policy$executed_score)
aggregated_score_util_drt_users_base <- sum(drt_users_base$executed_score)
aggregated_tt_h_drt_users_policy <- sum(trips_drt_users_policy$trav_time_s) / 3600
aggregated_tt_h_drt_users_base <- sum(trips_drt_users_base$trav_time_s) / 3600

aggregated <- data.frame(
  case=c("base","policy","diff_policy_minus_base"),
  all_aggr_score_util=c(aggregated_score_util_base,aggregated_score_util_policy,aggregated_score_util_policy-aggregated_score_util_base),
  drt_users_aggr_score_util=c(aggregated_score_util_drt_users_base,aggregated_score_util_drt_users_policy,aggregated_score_util_drt_users_policy-aggregated_score_util_drt_users_base),
  all_aggr_tt_h=c(aggregated_tt_h_base,aggregated_tt_h_policy,aggregated_tt_h_policy-aggregated_tt_h_base),
  drt_users_aggr_tt_h=c(aggregated_tt_h_drt_users_base,aggregated_tt_h_drt_users_policy,aggregated_tt_h_drt_users_policy-aggregated_tt_h_drt_users_base)
)

file_name <- "score_and_tt_aggr_042026.csv"
write_csv(aggregated, file=file_name)
print(paste("aggregated stats written to", file_name, "in dir", getwd()))


