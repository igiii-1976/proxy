import requests
import time
import threading
import uuid
import os
import csv
import random
from datetime import datetime

# --- Configuration ---
PHONE_IP = "10.220.230.97"
SERVER_PORT = 8080
BASE_URL = f"http://{PHONE_IP}:{SERVER_PORT}"
RECOGNIZE_URL = f"{BASE_URL}/recognize"
BATTERY_URL = f"{BASE_URL}/battery"

# Settings
IMAGE_TO_SEND = "test_image_1.jpg"
NUM_THREADS = 3
REQUEST_DELAY = 0.5
CSV_FILE = "test_results.csv"

# Thread-safe lock for writing to the CSV file
csv_lock = threading.Lock()

def get_timestamp():
    return datetime.now().strftime('%Y-%m-%d %H:%M:%S.%f')[:-3]

def initialize_csv():
    """Creates the CSV file with headers if it doesn't exist."""
    if not os.path.exists(CSV_FILE):
        with open(CSV_FILE, mode='w', newline='') as f:
            writer = csv.writer(f)
            writer.writerow([
                "Request_ID",
                "Endpoint",
                "Timestamp_Sent",
                "Timestamp_Received",
                "Status"
            ])

def log_to_csv(request_id, endpoint, sent_ts, received_ts, status):
    """Writes a single request's data to the CSV file."""
    with csv_lock:
        with open(CSV_FILE, mode='a', newline='') as f:
            writer = csv.writer(f)
            writer.writerow([
                request_id,
                endpoint,
                sent_ts,
                received_ts,
                status
            ])

def stress_test_worker(thread_id):
    """Infinite loop for a single thread to send mixed workload requests."""
    print(f"[{get_timestamp()}] [Thread-{thread_id}] Started.")

    if not os.path.exists(IMAGE_TO_SEND):
        with open(IMAGE_TO_SEND, 'wb') as f:
            f.write(b'\0')

    while True:
        client_request_id = str(uuid.uuid4())

        status_result = "Failed"
        ts_sent = "N/A"
        ts_received = "N/A"

        # Randomly choose endpoint
        endpoint_choice = random.choice(["/recognize", "/battery"])

        try:
            ts_sent = get_timestamp()
            print(f"[{ts_sent}] [Thread-{thread_id}] Sending {endpoint_choice}: {client_request_id}")

            # ==============================
            # LONG TASK → IMAGE RECOGNITION
            # ==============================
            if endpoint_choice == "/recognize":
                with open(IMAGE_TO_SEND, 'rb') as f:
                    files = {'imageFile': (IMAGE_TO_SEND, f, 'image/jpeg')}

                    response = requests.post(
                        RECOGNIZE_URL,
                        files=files,
                        headers={"X-Client-Request-ID": client_request_id},
                        timeout=10
                    )

                if response.status_code == 202:
                    task_id = response.json().get("taskId")
                    poll_url = f"{BASE_URL}/result/{task_id}"

                    is_finished = False
                    while not is_finished:
                        time.sleep(0.5)
                        poll_response = requests.get(
                            poll_url,
                            headers={"X-Client-Request-ID": client_request_id},
                            timeout=5
                        )

                        if poll_response.status_code == 200:
                            data = poll_response.json()
                            status = data.get("status")

                            if status == "complete":
                                ts_received = get_timestamp()
                                status_result = "Success"
                                is_finished = True
                                print(f"[{ts_received}] [Thread-{thread_id}] ✅ COMPLETE: {client_request_id}")

                            elif status == "error":
                                ts_received = get_timestamp()
                                status_result = "AI_Error"
                                is_finished = True

                        elif poll_response.status_code != 404:
                            ts_received = get_timestamp()
                            status_result = f"Poll_Err_{poll_response.status_code}"
                            is_finished = True

                else:
                    ts_received = get_timestamp()
                    status_result = f"Error_{response.status_code}"

            # ==============================
            # SHORT TASK → BATTERY REQUEST
            # ==============================
            else:
                response = requests.get(
                    BATTERY_URL,
                    headers={"X-Client-Request-ID": client_request_id},
                    timeout=5
                )

                ts_received = get_timestamp()

                if response.status_code == 200:
                    status_result = "Battery_Success"
                else:
                    status_result = f"Battery_Error_{response.status_code}"

        except Exception as e:
            ts_received = get_timestamp()
            status_result = f"Exception: {type(e).__name__}"
            print(f"[{ts_received}] [Thread-{thread_id}] ❌ Connection Failed: {e}")

        # Save to CSV
        log_to_csv(
            client_request_id,
            endpoint_choice,
            ts_sent,
            ts_received,
            status_result
        )

        time.sleep(REQUEST_DELAY)

if __name__ == "__main__":
    initialize_csv()

    print(f"--- Starting Mixed Workload Stress Test ---")
    print(f"Logging to: {CSV_FILE}")
    print(f"Threads: {NUM_THREADS}\n")

    threads = []
    try:
        for i in range(NUM_THREADS):
            t = threading.Thread(
                target=stress_test_worker,
                args=(i,),
                daemon=True
            )
            threads.append(t)
            t.start()
            time.sleep(0.1)

        while True:
            time.sleep(1)

    except KeyboardInterrupt:
        print("\nStopping stress test. CSV file finalized.")
