package org.janelia.saalfeldlab.n5.spark.downsample.scalepyramid;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.spark.N5RemoveSpark;
import org.janelia.saalfeldlab.n5.spark.N5WriterSupplier;
import org.janelia.saalfeldlab.n5.spark.downsample.N5DownsamplerSpark;
import org.janelia.saalfeldlab.n5.spark.util.CmdUtils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import net.imglib2.util.Util;

public class N5NonIsotropicScalePyramidSpark3D
{
	static class NonIsotropicMetadata
	{
		public final long[] dimensions;
		public final int[] cellSize;
		public final int[] downsamplingFactors;

		public NonIsotropicMetadata(
				final long[] dimensions,
				final int[] cellSize,
				final int[] downsamplingFactors )
		{
			this.dimensions = dimensions;
			this.cellSize = cellSize;
			this.downsamplingFactors = downsamplingFactors;
		}
	}

	static class NonIsotropicScalePyramidMetadata
	{
		static enum MainDimension
		{
			XY,
			Z
		}

		private static final double EPSILON = 1e-10;

		public final List< NonIsotropicMetadata > scalesMetadata;
		public final MainDimension mainDimension;
		public final boolean isPowerOfTwo;
		private final double pixelResolutionRatio;

		public NonIsotropicScalePyramidMetadata(
				final long[] fullScaleDimensions,
				final int[] fullScaleCellSize,
				final double[] pixelResolution,
				final boolean isPowerOfTwo )
		{
			// determine the main dimension which is the one with better resolution
			final double ratioZtoXY = getPixelResolutionRatioZtoXY( pixelResolution );
			if ( ratioZtoXY > 1 )
			{
				mainDimension = MainDimension.XY;
				pixelResolutionRatio = ratioZtoXY;
			}
			else
			{
				mainDimension = MainDimension.Z;
				pixelResolutionRatio = 1. / ratioZtoXY;
			}

			this.isPowerOfTwo = isPowerOfTwo || (
					Util.isApproxEqual( pixelResolutionRatio, Math.round( pixelResolutionRatio ), EPSILON )
					&& ( Math.round( pixelResolutionRatio ) & ( Math.round( pixelResolutionRatio ) - 1 ) ) == 0
				);

			scalesMetadata = new ArrayList<>();
			init( fullScaleDimensions, fullScaleCellSize, pixelResolution );
		}

		private void init(
				final long[] fullScaleDimensions,
				final int[] fullScaleCellSize,
				final double[] pixelResolution )
		{
			scalesMetadata.add( new NonIsotropicMetadata( fullScaleDimensions.clone(), fullScaleCellSize.clone(), new int[] { 1, 1, 1 } ) );
			for ( int scale = 1; ; ++scale )
			{
				final int mainDownsamplingFactor = 1 << scale;
				final double isotropicScaling = mainDownsamplingFactor / pixelResolutionRatio;
				final int dependentDownsamplingFactor = isPowerOfTwo ? getNearestPowerOfTwo( isotropicScaling ) : Math.max( 1, ( int ) Math.round( isotropicScaling ) );
				final int[] downsamplingFactors;
				if ( mainDimension == MainDimension.XY )
					downsamplingFactors = new int[] { mainDownsamplingFactor, mainDownsamplingFactor, dependentDownsamplingFactor };
				else
					downsamplingFactors = new int[] { dependentDownsamplingFactor, dependentDownsamplingFactor, mainDownsamplingFactor };

				final long[] downsampledDimensions = new long[ fullScaleDimensions.length ];
				for ( int d = 0; d < downsampledDimensions.length; ++d )
					downsampledDimensions[ d ] = fullScaleDimensions[ d ] / downsamplingFactors[ d ];
				if ( Arrays.stream( downsampledDimensions ).min().getAsLong() < 1 )
					break;

				final int mainCellSize, dependentPreviousCellSize;
				if ( mainDimension == MainDimension.XY )
				{
					mainCellSize = Math.max( fullScaleCellSize[ 0 ], fullScaleCellSize[ 1 ] );
					dependentPreviousCellSize = scalesMetadata.get( scale - 1 ).cellSize[ 2 ];
				}
				else
				{
					mainCellSize = fullScaleCellSize[ 2 ];
					dependentPreviousCellSize = Math.max( scalesMetadata.get( scale - 1 ).cellSize[ 0 ], scalesMetadata.get( scale - 1 ).cellSize[ 1 ] );
				}
				final int dependentFullScaleOptimalCellSize = ( int ) Math.round( mainCellSize / pixelResolutionRatio );
				final int dependentOptimalCellSize = ( int ) Math.round( ( long ) dependentFullScaleOptimalCellSize * mainDownsamplingFactor / ( double ) dependentDownsamplingFactor );
				final int dependentMultipleCellSize = ( int ) Math.round( dependentOptimalCellSize / ( double ) dependentPreviousCellSize ) * dependentPreviousCellSize;
				final int dependentMaxCellSize = Math.max( ( int ) Math.round( dependentFullScaleOptimalCellSize * pixelResolutionRatio ), mainCellSize );
				final int dependentAdjustedCellSize = Math.min( dependentMultipleCellSize, ( dependentMaxCellSize / dependentPreviousCellSize ) * dependentPreviousCellSize );
				final int[] downsampledCellSize;
				if ( mainDimension == MainDimension.XY )
					downsampledCellSize = new int[] { fullScaleCellSize[ 0 ], fullScaleCellSize[ 1 ], dependentAdjustedCellSize };
				else
					downsampledCellSize = new int[] { dependentAdjustedCellSize, dependentAdjustedCellSize, fullScaleCellSize[ 2 ] };

				scalesMetadata.add( new NonIsotropicMetadata( downsampledDimensions, downsampledCellSize, downsamplingFactors ) );
			}
		}

		public int getNumScales()
		{
			return scalesMetadata.size();
		}

		public NonIsotropicMetadata getScaleMetadata( final int scale )
		{
			return scalesMetadata.get( scale );
		}

		public int getDependentDownsamplingFactor( final int scale )
		{
			final NonIsotropicMetadata scaleMetadata = getScaleMetadata( scale );
			if ( mainDimension == MainDimension.XY )
				return scaleMetadata.downsamplingFactors[ 2 ];
			else
				return Math.max( scaleMetadata.downsamplingFactors[ 0 ], scaleMetadata.downsamplingFactors[ 1 ] );
		}

		public int[] getIntermediateDownsamplingFactors( final int scale )
		{
			final NonIsotropicMetadata scaleMetadata = getScaleMetadata( scale ), previousScaleMetadata = getScaleMetadata( scale - 1 );
			if ( mainDimension == MainDimension.XY )
				return new int[] { scaleMetadata.downsamplingFactors[ 0 ] / previousScaleMetadata.downsamplingFactors[ 0 ], scaleMetadata.downsamplingFactors[ 1 ] / previousScaleMetadata.downsamplingFactors[ 1 ], 1 };
			else
				return new int[] { 1, 1, scaleMetadata.downsamplingFactors[ 2 ] / previousScaleMetadata.downsamplingFactors[ 2 ] };
		}

		public static double getPixelResolutionRatioZtoXY( final double[] pixelResolution )
		{
			if ( pixelResolution == null )
				return 1;
			return pixelResolution[ 2 ] / Math.max( pixelResolution[ 0 ], pixelResolution[ 1 ] );
		}

		private static int getNearestPowerOfTwo( final double value )
		{
			return 1 << Math.max( 0, Math.round( ( Math.log( value ) / Math.log( 2 ) ) ) );
		}
	}

	private static final String DOWNSAMPLING_FACTORS_ATTRIBUTE_KEY = "downsamplingFactors";
	private static final String PIXEL_RESOLUTION_ATTRIBUTE_KEY = "pixelResolution";

	/**
	 * Generates a scale pyramid for a given dataset (3D only). Assumes that the pixel resolution is the same in X and Y.
	 * The scale pyramid is constructed in the following way depending on the pixel resolution of the data:<br>
	 * - if the resolution is better in X/Y than in Z: each scale level is downsampled by 2 in X/Y, and by the corresponding factors in Z to be as close as possible to isotropic<br>
	 * - if the resolution is better in Z than in X/Y: each scale level is downsampled by 2 in Z, and by the corresponding factors in X/Y to be as close as possible to isotropic<br>
	 *<p>
	 * Adjusts the block size to be consistent with the scaling factors. Stores the resulting datasets in the same group as the input dataset.
	 *
	 * @param sparkContext
	 * @param n5Supplier
	 * @param fullScaleDatasetPath
	 * @param pixelResolution
	 * @param isPowerOfTwo
	 * @return N5 paths to downsampled datasets
	 * @throws IOException
	 */
	public static List< String > downsampleNonIsotropicScalePyramid(
			final JavaSparkContext sparkContext,
			final N5WriterSupplier n5Supplier,
			final String fullScaleDatasetPath,
			final double[] pixelResolution,
			final boolean isPowerOfTwo ) throws IOException
	{
		final String outputGroupPath = ( Paths.get( fullScaleDatasetPath ).getParent() != null ? Paths.get( fullScaleDatasetPath ).getParent().toString() : "" );
		return downsampleNonIsotropicScalePyramid(
				sparkContext,
				n5Supplier,
				fullScaleDatasetPath,
				outputGroupPath,
				pixelResolution,
				isPowerOfTwo
			);
	}

	/**
	 * Generates a scale pyramid for a given dataset (3D only). Assumes that the pixel resolution is the same in X and Y.
	 * The scale pyramid is constructed in the following way depending on the pixel resolution of the data:<br>
	 * - if the resolution is better in X/Y than in Z: each scale level is downsampled by 2 in X/Y, and by the corresponding factors in Z to be as close as possible to isotropic<br>
	 * - if the resolution is better in Z than in X/Y: each scale level is downsampled by 2 in Z, and by the corresponding factors in X/Y to be as close as possible to isotropic<br>
	 *<p>
	 * Adjusts the block size to be consistent with the scaling factors. Stores the resulting datasets in the given output group.
	 *
	 * @param sparkContext
	 * @param n5Supplier
	 * @param fullScaleDatasetPath
	 * @param outputGroupPath
	 * @param pixelResolution
	 * @param isPowerOfTwo
	 * @return N5 paths to downsampled datasets
	 * @throws IOException
	 */
	public static List< String > downsampleNonIsotropicScalePyramid(
			final JavaSparkContext sparkContext,
			final N5WriterSupplier n5Supplier,
			final String fullScaleDatasetPath,
			final String outputGroupPath,
			final double[] pixelResolution,
			final boolean isPowerOfTwo ) throws IOException
	{
		if ( !Util.isApproxEqual( pixelResolution[ 0 ], pixelResolution[ 1 ], 1e-10 ) )
			throw new IllegalArgumentException( "Pixel resolution is different in X/Y" );

		final N5Writer n5 = n5Supplier.get();
		final DatasetAttributes fullScaleAttributes = n5.getDatasetAttributes( fullScaleDatasetPath );
		final long[] fullScaleDimensions = fullScaleAttributes.getDimensions();
		final int[] fullScaleCellSize = fullScaleAttributes.getBlockSize();

		final NonIsotropicScalePyramidMetadata scalePyramidMetadata = new NonIsotropicScalePyramidMetadata(
				fullScaleDimensions,
				fullScaleCellSize,
				pixelResolution,
				isPowerOfTwo
			);

		// prepare for intermediate downsampling if required
		final String intermediateGroupPath;
		if ( !scalePyramidMetadata.isPowerOfTwo )
		{
			System.out.println( "Not a power of two, intermediate downsampling in " + scalePyramidMetadata.mainDimension + " is required" );
			intermediateGroupPath = Paths.get( outputGroupPath, "intermediate-downsampling-" + scalePyramidMetadata.mainDimension ).toString();
			if ( n5.exists( intermediateGroupPath ) )
				throw new RuntimeException( "Group for intermediate downsampling in " + scalePyramidMetadata.mainDimension + " already exists: " + intermediateGroupPath );
			n5.createGroup( intermediateGroupPath );
		}
		else
		{
			System.out.println( "Power of two, skip intermediate downsampling in " + scalePyramidMetadata.mainDimension );
			intermediateGroupPath = null;
		}

		// check for existence of output datasets and fail if any of them already exist
		// it is safer to do so because otherwise the user may accidentally overwrite useful data
		for ( int scale = 1; scale < scalePyramidMetadata.getNumScales(); ++scale )
		{
			final String outputDatasetPath = Paths.get( outputGroupPath, "s" + scale ).toString();
			if ( n5.datasetExists( outputDatasetPath ) )
				throw new RuntimeException( "Output dataset already exists: " + outputDatasetPath );
		}

		final List< String > downsampledDatasets = new ArrayList<>();
		for ( int scale = 1; scale < scalePyramidMetadata.getNumScales(); ++scale )
		{
			final NonIsotropicMetadata scaleMetadata = scalePyramidMetadata.getScaleMetadata( scale );
			final String outputDatasetPath = Paths.get( outputGroupPath, "s" + scale ).toString();

			if ( scalePyramidMetadata.isPowerOfTwo || scalePyramidMetadata.getDependentDownsamplingFactor( scale ) == 1 )
			{
				final String inputDatasetPath = scale == 1 ? fullScaleDatasetPath : Paths.get( outputGroupPath, "s" + ( scale - 1 ) ).toString();

				// intermediate downsampling is not happening yet at this scale level, or is not required at all
				final NonIsotropicMetadata previousScaleMetadata = scalePyramidMetadata.getScaleMetadata( scale - 1 );
				final int[] relativeDownsamplingFactors = new int[ scaleMetadata.downsamplingFactors.length ];
				for ( int d = 0; d < relativeDownsamplingFactors.length; ++d )
				{
					if ( scaleMetadata.downsamplingFactors[ d ] % previousScaleMetadata.downsamplingFactors[ d ] != 0 )
						throw new RuntimeException( "something went wrong, expected divisible downsampling factors" );
					relativeDownsamplingFactors[ d ] = scaleMetadata.downsamplingFactors[ d ] / previousScaleMetadata.downsamplingFactors[ d ];
				}

				N5DownsamplerSpark.downsample(
						sparkContext,
						n5Supplier,
						inputDatasetPath,
						outputDatasetPath,
						relativeDownsamplingFactors,
						scaleMetadata.cellSize
					);
			}
			else
			{
				final String inputDatasetPath;
				if ( scalePyramidMetadata.getDependentDownsamplingFactor( scale - 1 ) == 1 )
				{
					// this is the first scale level where intermediate downsampling is required
					inputDatasetPath = scale == 1 ? fullScaleDatasetPath : Paths.get( outputGroupPath, "s" + ( scale - 1 ) ).toString();
				}
				else
				{
					// there exists an intermediate downsampled export
					inputDatasetPath = Paths.get( intermediateGroupPath, "s" + ( scale - 1 ) ).toString();
				}

				final String intermediateDatasetPath = Paths.get( intermediateGroupPath, "s" + scale ).toString();
				final int[] intermediateDownsamplingFactors = scalePyramidMetadata.getIntermediateDownsamplingFactors( scale );

				// downsample and store in the intermediate export group
				N5DownsamplerSpark.downsample(
						sparkContext,
						n5Supplier,
						inputDatasetPath,
						intermediateDatasetPath,
						intermediateDownsamplingFactors,
						scaleMetadata.cellSize
					);

				final int[] relativeDownsamplingFactors = new int[ intermediateDownsamplingFactors.length ];
				for ( int d = 0; d < relativeDownsamplingFactors.length; ++d )
					relativeDownsamplingFactors[ d ] = intermediateDownsamplingFactors[ d ] == 1 ? scaleMetadata.downsamplingFactors[ d ] : 1;

				// downsample and store in the output group
				N5DownsamplerSpark.downsample(
						sparkContext,
						n5Supplier,
						intermediateDatasetPath,
						outputDatasetPath,
						relativeDownsamplingFactors,
						scaleMetadata.cellSize
					);
			}

			n5.setAttribute( outputDatasetPath, DOWNSAMPLING_FACTORS_ATTRIBUTE_KEY, scaleMetadata.downsamplingFactors );
			n5.setAttribute( outputDatasetPath, PIXEL_RESOLUTION_ATTRIBUTE_KEY, pixelResolution );

			downsampledDatasets.add( outputDatasetPath );
		}

		if ( !scalePyramidMetadata.isPowerOfTwo )
			N5RemoveSpark.remove( sparkContext, n5Supplier, intermediateGroupPath );

		return downsampledDatasets;
	}


	public static void main( final String... args ) throws IOException
	{
		final Arguments parsedArgs = new Arguments( args );

		try ( final JavaSparkContext sparkContext = new JavaSparkContext( new SparkConf()
				.setAppName( "N5NonIsotropicScalePyramidSpark3D" )
				.set( "spark.serializer", "org.apache.spark.serializer.KryoSerializer" )
			) )
		{
			final N5WriterSupplier n5Supplier = () -> new N5FSWriter( parsedArgs.getN5Path() );

			if ( parsedArgs.getOutputGroupPath() != null )
			{
				downsampleNonIsotropicScalePyramid(
						sparkContext,
						n5Supplier,
						parsedArgs.getInputDatasetPath(),
						parsedArgs.getOutputGroupPath(),
						parsedArgs.getPixelResolution(),
						parsedArgs.getIsPowerOfTwo()
					);
			}
			else
			{
				downsampleNonIsotropicScalePyramid(
						sparkContext,
						n5Supplier,
						parsedArgs.getInputDatasetPath(),
						parsedArgs.getPixelResolution(),
						parsedArgs.getIsPowerOfTwo()
					);
			}
		}
	}

	private static class Arguments implements Serializable
	{
		private static final long serialVersionUID = -1467734459169624759L;

		@Option(name = "-n", aliases = { "--n5Path" }, required = true,
				usage = "Path to an N5 container.")
		private String n5Path;

		@Option(name = "-i", aliases = { "--inputDatasetPath" }, required = true,
				usage = "Path to an input dataset within the N5 container (e.g. data/group/s0).")
		private String inputDatasetPath;

		@Option(name = "-o", aliases = { "--outputGroupPath" }, required = false,
				usage = "Path to a group within the N5 container to store the output datasets (e.g. data/group/scale-pyramid).")
		private String outputGroupPath;

		@Option(name = "-r", aliases = { "--pixelResolution" }, required = true,
				usage = "Pixel resolution of the data in um (microns). Depending on whether the resolution is better in X/Y than in Z or vice versa, the downsampling factors are adjusted to make the scale levels as close to isotropic as possible.")
		private String pixelResolution;

		@Option(name = "-p", aliases = { "--powerOfTwo" }, required = false,
				usage = "Forces to generate a power-of-two scale pyramid that is as close to isotropic as possible.")
		private boolean isPowerOfTwo;

		public Arguments( final String... args ) throws IllegalArgumentException
		{
			final CmdLineParser parser = new CmdLineParser( this );
			try
			{
				parser.parseArgument( args );
			}
			catch ( final CmdLineException e )
			{
				System.err.println( e.getMessage() );
				parser.printUsage( System.err );
				System.exit( 1 );
			}
		}

		public String getN5Path() { return n5Path; }
		public String getInputDatasetPath() { return inputDatasetPath; }
		public String getOutputGroupPath() { return outputGroupPath; }
		public double[] getPixelResolution() { return CmdUtils.parseDoubleArray( pixelResolution ); }
		public boolean getIsPowerOfTwo() { return isPowerOfTwo; }
	}
}
