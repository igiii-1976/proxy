import requests
import random
import time
import os

# --- Configuration ---# IMPORTANT: Replace with your Android device's IP address
DEVICE_IP = "192.168.1.3" 
PORT = 8080
BASE_URL = f"http://{DEVICE_IP}:{PORT}"

# File to upload for the /recognize endpoint
IMAGE_TO_UPLOAD = "test_image.jfif"

# File to download from the app's assets via the /download endpoint
ASSET_TO_DOWNLOAD = "example.txt"

# --- End of Configuration ---

endpoint_tally = {
    "recognize": 0,
    "download": 0,
    "failures": 0
}

def test_recognize_endpoint():
    """
    Sends an image to the /recognize endpoint and prints the result.
    """
    global endpoint_tally # Use the global tally
    endpoint_tally["recognize"] += 1 # Increment the count for this endpoint

    print("--- Testing: /recognize ---")
    url = f"{BASE_URL}/recognize"

    if not os.path.exists(IMAGE_TO_UPLOAD):
        print(f"Error: Test image '{IMAGE_TO_UPLOAD}' not found.")
        print("Please create a dummy image file with this name to run the test.")
        endpoint_tally["failures"] += 1
        return

    try:
        with open(IMAGE_TO_UPLOAD, 'rb') as image_file:
            files = {'imageFile': (IMAGE_TO_UPLOAD, image_file, 'image/jpeg')}
            
            print(f"Sending POST request to {url} with image '{IMAGE_TO_UPLOAD}'...")
            response = requests.post(url, files=files, timeout=40)

        print(f"Status Code: {response.status_code}")

        if response.status_code == 200:
            print("Response JSON:")
            print(response.json())
        else:
            endpoint_tally["failures"] += 1 # Count non-200 responses as failures
            print("Error Response:")
            print(response.text)

    except requests.exceptions.RequestException as e:
        endpoint_tally["failures"] += 1 # Count request exceptions as failures
        print(f"An error occurred: {e}")


def test_download_endpoint():
    """
    Requests a file from the /download endpoint and checks the status.
    """
    global endpoint_tally # Use the global tally
    endpoint_tally["download"] += 1 # Increment the count for this endpoint

    print("--- Testing: /download ---")
    url = f"{BASE_URL}/download?file={ASSET_TO_DOWNLOAD}"
    
    try:
        print(f"Sending GET request to {url}...")
        response = requests.get(url, timeout=10)

        print(f"Status Code: {response.status_code}")

        if response.status_code == 200:
            file_size = len(response.content)
            print(f"Successfully received file '{ASSET_TO_DOWNLOAD}'. Size: {file_size} bytes.")
        else:
            endpoint_tally["failures"] += 1 # Count non-200 responses as failures
            print("Error Response:")
            print(response.text)

    except requests.exceptions.RequestException as e:
        endpoint_tally["failures"] += 1 # Count request exceptions as failures
        print(f"An error occurred: {e}")


if __name__ == "__main__":
    print(f"Starting test suite for server at {BASE_URL}")
    print("Press Ctrl+C to stop.")

    request_count = 0
    try:
        while True:
            request_count += 1
            print(f"\n--- Request #{request_count} ---")
            
            # Randomly choose which endpoint to test
            if random.random() < 0.5:
                test_recognize_endpoint()
            else:
                test_download_endpoint()
            
            # Wait for a few seconds before the next request
            time.sleep(0.3)
            
    except KeyboardInterrupt:
        print("\nTest stopped by user.")
        print("\n-- Endpoint Trigger Summary --")
        print("-" * 30)
        for endpoint, count in endpoint_tally.items():
            print(f"{endpoint.ljust(15)}: {count} times")
        print("-" * 30)