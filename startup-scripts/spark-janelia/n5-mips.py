#!/usr/bin/env python

import os
import sys
import subprocess

curr_script_dir = os.path.dirname(os.path.realpath(__file__))
base_folder = os.path.dirname(os.path.dirname(curr_script_dir))
bin_file = os.path.join('target', 'n5-spark-1.0.1-SNAPSHOT.jar')
bin_path = os.path.join(base_folder, bin_file)
flintstone_file = os.path.join('flintstone', 'flintstone.sh')
flintstone_path = os.path.join(curr_script_dir, flintstone_file)

os.environ['SPARK_VERSION'] = '2'
os.environ['N_DRIVER_THREADS'] = '2'
os.environ['MEMORY_PER_NODE'] = '115'
os.environ['TERMINATE'] = '1'

nodes = int(sys.argv[1])

subprocess.call([flintstone_path, str(nodes), bin_path, 'org.janelia.saalfeldlab.n5.spark.N5MaxIntensityProjection'] + sys.argv[2:])