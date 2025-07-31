library(tidyverse)
library(lubridate)

# DEPRECATED SCRIPT

ptPersons <- read.csv(file="Y:/net/ils/matsim-lausitz/caseStudies/v2024.2/pt-case-study/output-lausitz-pt-case/analysis/analysis/pt_persons.csv")

ptCaseTrips <- read.csv2(file="Y:/net/ils/matsim-lausitz/caseStudies/v2024.2/pt-case-study/output-lausitz-pt-case/lausitz-pt-case.output_trips.csv.gz")
drtCaseTrips <- read.csv2(file="Y:/net/ils/matsim-lausitz/caseStudies/v2024.2/drt-case-study/output-lausitz-drt-case-manual-trip-replacement/lausitz-drt-case.output_trips.csv.gz")

ptCasePersons <- read.csv2(file="Y:/net/ils/matsim-lausitz/caseStudies/v2024.2/pt-case-study/output-lausitz-pt-case/lausitz-pt-case.output_persons.csv.gz")
drtCasePersons <- read.csv2(file="Y:/net/ils/matsim-lausitz/caseStudies/v2024.2/drt-case-study/output-lausitz-drt-case-manual-trip-replacement/lausitz-drt-case.output_persons.csv.gz")

ptPersons <- ptPersons %>% 
  mutate(person=as.character(person),
         time = as.double(time)) %>% 
  distinct(person, .keep_all=TRUE)

filteredPtCase <- ptCaseTrips %>% 
  filter(person %in% ptPersons$person) %>% 
  left_join(ptPersons, by="person") %>% 
  mutate(dep_time = hms(dep_time),
         dep_s = as.numeric(hms(dep_time)),
         trav_s = as.numeric(hms(trav_time))) %>% 
  filter(time >= dep_s & time <= dep_s + trav_s)

filteredPersonsPtCase <- ptCasePersons %>% 
  mutate(executed_score = as.numeric(executed_score)) %>% 
  filter(person %in% ptPersons$person)

filteredPersonsDrtCase <- drtCasePersons %>% 
  mutate(executed_score = as.numeric(executed_score)) %>%
  filter(person %in% ptPersons$person)

meanTravTimePtCase <- mean(filteredPtCase$trav_s)
meanTravDistPtCase <- mean(filteredPtCase$traveled_distance)

meanScorePtCase <- mean(filteredPersonsPtCase$executed_score)
meanScoreDrtCase <- mean(filteredPersonsDrtCase$executed_score)

filteredDrtCase <- drtCaseTrips %>% 
  filter(person %in% ptPersons$person) %>% 
  left_join(ptPersons, by="person") %>% 
  mutate(dep_time = hms(dep_time),
         dep_s = as.numeric(hms(dep_time)),
         trav_s = as.numeric(hms(trav_time))) %>% 
  filter(time >= dep_s & time <= dep_s + trav_s) %>% 
  mutate(dep_hour = as.numeric(hour(dep_time)))

meanTravTimeDrtCase <- mean(filteredDrtCase$trav_s)
meanTravDistDrtCase <- mean(filteredDrtCase$traveled_distance)

# write.csv(filteredPtCase, file="C:/Users/Simon/Desktop/wd/2025-03-03/lausitz_ptCase_test.csv", quote = FALSE, row.names = FALSE)

ggplot(filteredDrtCase, aes(x = dep_hour)) +
  geom_histogram(binwidth = 1, fill = "steelblue", color = "black", alpha = 0.7) +
  scale_x_continuous(breaks = seq(0, 24, 1)) +
  scale_y_continuous(breaks = seq(0, max(table(filteredDrtCase$dep_hour)), 1)) +
  labs(title = "Distribution of Departure Hours",
       x = "Departure Hour",
       y = "Frequency") +
  theme_minimal()


comparison <- inner_join(filteredPtCase, filteredDrtCase, by="person", suffix = c(".ptCase", ".drtCase")) %>% 
  select(person, trip_id.ptCase, trav_s.ptCase, traveled_distance.ptCase, main_mode.ptCase,
         trip_id.drtCase, trav_s.drtCase, traveled_distance.drtCase, main_mode.drtCase) %>% 
  mutate(delta_trav_s = trav_s.ptCase - trav_s.drtCase,
         delta_trav_dist = traveled_distance.ptCase - traveled_distance.drtCase)

summary <- comparison %>% 
  summarize(mean_trav_s_ptCase=mean(trav_s.ptCase),
            mean_trav_s_drtCase=mean(trav_s.drtCase),
            mean_trav_dist_ptCase=mean(traveled_distance.ptCase),
            mean_trav_dist_drtCase=mean(traveled_distance.drtCase),
            mean_trav_s_delta=mean(delta_trav_s),
            mean_trav_dist_delta=mean(delta_trav_dist)) %>% 
  mutate(mean_trav_min_ptCase=mean_trav_s_ptCase / 60,
         mean_trav_min_drtCase=mean_trav_s_drtCase / 60)

write.csv(summary, file="C:/Users/Simon/Desktop/wd/2024-09-23/lausitz_ptCase_drtCase_summary.csv", quote = FALSE, row.names = FALSE)

# Reshape the data to a long format for easy plotting
df_long <- summary %>%
  pivot_longer(
    cols = c(mean_trav_min_ptCase, mean_trav_min_drtCase, 
             mean_trav_dist_ptCase, mean_trav_dist_drtCase),
    names_to = c("metric", "Case"),
    names_pattern = "(mean_trav_[^_]+)_(ptCase|drtCase)"
  )

# Create the barplot for mean_trav_min and mean_trav_dist
ggplot(df_long, aes(x = Case, y = value, fill = Case)) +
  geom_bar(stat = "identity", position = "dodge") +
  facet_wrap(~ metric, scales = "free_y") +
  labs(title = "Comparison of mean_trav_min and mean_trav_dist by Case",
       x = "Case", y = "Value") +
  theme_minimal()
  





