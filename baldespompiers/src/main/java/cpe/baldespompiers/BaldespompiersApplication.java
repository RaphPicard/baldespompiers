package cpe.baldespompiers;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class BaldespompiersApplication {

	public static void main(String[] args) {
		SpringApplication.run(BaldespompiersApplication.class, args);
	}

}

// Pour raph le nul qui arrive pas à lancer : (ne pas supprimer please)
// lsof -i :8080
// kill ...
// Maven --> package (.jar)
// cd /Users/RaphaelPICARD/Desktop/CLBD/PROJET_CLBD/PM/baldespompiers
//  java -jar target/baldespompiers-0.0.1-SNAPSHOT.jar
