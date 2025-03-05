package com.example.drone;

import com.example.drone.client.MavlinkClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DroneApplication implements CommandLineRunner {


	private final MavlinkClient mavlinkClient;

    public DroneApplication(MavlinkClient mavlinkClient) {
        this.mavlinkClient = mavlinkClient;
    }

    public static void main(String[] args) {
		SpringApplication.run(DroneApplication.class, args);
	}


	@Override
	public void run(String... args) throws Exception {
		mavlinkClient.startListening();

//		new Thread(mavlinkClient::printTelemetryData).start();
	}
}
