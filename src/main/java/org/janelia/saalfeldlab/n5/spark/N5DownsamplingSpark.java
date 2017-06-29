package org.janelia.saalfeldlab.n5.spark;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;

import bdv.export.Downsample;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import scala.Tuple2;

public class N5DownsamplingSpark< T extends NativeType< T > & RealType< T > >
{
	private final transient JavaSparkContext sparkContext;

	/**
	 * Takes an existing spark context to run the downsampling code.
	 * Assumes that the spark context is instantiated using Kryo serializer (see {@link N5DownsamplingSparkTest} for reference)
	 * as the downsampling code serializes objects of type {@link Interval}.
	 *
	 * @param sparkContext
	 */
	public N5DownsamplingSpark( final JavaSparkContext sparkContext )
	{
		this.sparkContext = sparkContext;
	}

	/**
	 * Generates lower scale levels for a given dataset. Each scale level is downsampled by 2 in all dimensions.
	 * Stops generating scale levels once the size of the resulting volume is smaller than the block size in any dimension.
	 * Reuses the block size of the given dataset.
	 *
	 * @param basePath Path to the N5 root
	 * @param datasetPath Path to the full-scale dataset
	 *
	 * @return downsampling factors for all scales including the input (full scale)
	 */
	public int[][] downsample( final String basePath, final String datasetPath ) throws IOException
	{
		return downsampleIsotropic( basePath, datasetPath, null );
	}

	/**
	 * <p>
	 * Generates lower scale levels for a given dataset.
	 * Stops generating scale levels once the size of the resulting volume is smaller than the block size in any dimension.
	 * </p><p>
	 * Assumes that the pixel resolution is the same in X and Y.
	 * Each scale level is downsampled by 2 in XY, and by the corresponding factors in Z to be as close as possible to isotropic.
	 * Reuses the block size of the given dataset, and adjusts the block sizes in Z to be consistent with the scaling factors.
	 * </p>
	 *
	 * @param basePath Path to the N5 root
	 * @param datasetPath Path to the full-scale dataset
	 * @param voxelDimensions Pixel resolution of the data
	 *
	 * @return downsampling factors for all scales including the input (full scale)
	 */
	public int[][] downsampleIsotropic( final String basePath, final String datasetPath, final VoxelDimensions voxelDimensions ) throws IOException
	{
		final double pixelResolutionZToXY = ( voxelDimensions != null ? getPixelResolutionZtoXY( voxelDimensions ) : 1 );
		final boolean needIntermediateDownsamplingInXY = ( pixelResolutionZToXY != 1 );

		final N5Writer n5 = N5.openFSWriter( basePath );
		final DatasetAttributes fullScaleAttributes = n5.getDatasetAttributes( datasetPath );

		final long[] fullScaleDimensions = fullScaleAttributes.getDimensions();
		final int[] fullScaleCellSize = fullScaleAttributes.getBlockSize();

		final List< int[] > scales = new ArrayList<>();
		scales.add( new int[] { 1, 1, 1 } );

		final String rootOutputPath = ( Paths.get( datasetPath ).getParent() != null ? Paths.get( datasetPath ).getParent().toString() : "" );
		final String xyGroupPath = Paths.get( rootOutputPath, "xy" ).toString();

		// loop over scale levels
		for ( int scale = 1; ; ++scale )
		{
			final int[] cellSize = fullScaleCellSize.clone();
			final Tuple2< Integer, Integer > zCellSizeAndScale = getOptimalZCellSizeAndDownsamplingFactor( scale, Math.max( cellSize[ 0 ], cellSize[ 1 ] ), pixelResolutionZToXY );
			cellSize[ 2 ] = zCellSizeAndScale._1();

			final int[] downsamplingFactors = new int[] { 1 << scale, 1 << scale, zCellSizeAndScale._2() };

			final long[] downsampledDimensions = fullScaleDimensions.clone();
			for ( int d = 0; d < downsampledDimensions.length; ++d )
				downsampledDimensions[ d ] /= downsamplingFactors[ d ];

			if ( Arrays.stream( downsampledDimensions ).min().getAsLong() <= 1 || Arrays.stream( downsampledDimensions ).max().getAsLong() <= Arrays.stream( cellSize ).max().getAsInt() )
				break;

			if ( !needIntermediateDownsamplingInXY )
			{
				// downsample in XYZ
				final String inputDatasetPath = scale == 1 ? datasetPath : Paths.get( rootOutputPath, "s" + ( scale - 1 ) ).toString();
				final String outputDatasetPath = Paths.get( rootOutputPath, "s" + scale ).toString();
				n5.createDataset(
						outputDatasetPath,
						downsampledDimensions,
						cellSize,
						fullScaleAttributes.getDataType(),
						fullScaleAttributes.getCompressionType()
					);
				downsample( basePath, inputDatasetPath, outputDatasetPath );
			}
			else
			{
				// downsample in XY
				final String inputXYDatasetPath = scale == 1 ? datasetPath : Paths.get( xyGroupPath, "s" + ( scale - 1 ) ).toString();
				final String outputXYDatasetPath = Paths.get( xyGroupPath, "s" + scale ).toString();
				n5.createDataset(
						outputXYDatasetPath,
						new long[] { downsampledDimensions[ 0 ], downsampledDimensions[ 1 ], fullScaleDimensions[ 2 ] },
						new int[] { cellSize[ 0 ], cellSize[ 1 ], fullScaleCellSize[ 2 ] },
						fullScaleAttributes.getDataType(),
						fullScaleAttributes.getCompressionType()
					);
				downsample( basePath, inputXYDatasetPath, outputXYDatasetPath );

				// downsample in Z
				final String inputDatasetPath = outputXYDatasetPath;
				final String outputDatasetPath = Paths.get( rootOutputPath, "s" + scale ).toString();
				n5.createDataset(
						outputDatasetPath,
						downsampledDimensions,
						cellSize,
						fullScaleAttributes.getDataType(),
						fullScaleAttributes.getCompressionType()
					);
				downsample( basePath, inputDatasetPath, outputDatasetPath );
			}

			scales.add( downsamplingFactors );
		}

		if ( needIntermediateDownsamplingInXY )
			new N5RemoveSpark( sparkContext ).remove( basePath, xyGroupPath );

		return scales.toArray( new int[ 0 ][] );
	}

	private void downsample( final String basePath, final String inputDatasetPath, final String outputDatasetPath ) throws IOException
	{
		final N5Reader n5 = N5.openFSReader( basePath );
		final DatasetAttributes inputAttributes = n5.getDatasetAttributes( inputDatasetPath );
		final DatasetAttributes outputAttributes = n5.getDatasetAttributes( outputDatasetPath );

		final long[] inputDimensions = inputAttributes.getDimensions();
		final long[] outputDimensions = outputAttributes.getDimensions();
		final int[] downsamplingFactors = new int[ inputDimensions.length ];
		for ( int d = 0; d < downsamplingFactors.length; ++d )
			downsamplingFactors[ d ] = ( int ) ( inputDimensions[ d ] / outputDimensions[ d ] );

		final int[] outputCellSize = outputAttributes.getBlockSize();
		final int dim = outputCellSize.length;

		final List< Tuple2< Interval, Interval > > sourceAndTargetIntervals = new ArrayList<>();
		final long[] offset = new long[ dim ], sourceMin = new long[ dim ], sourceMax = new long[ dim ], targetMin = new long[ dim ], targetMax = new long[ dim ];
		for ( int d = 0; d < dim; )
		{
			for ( int i = 0; i < dim; i++ )
			{
				targetMin[ i ] = offset[ i ];
				targetMax[ i ] = Math.min( targetMin[ i ] + outputCellSize[ i ], outputDimensions[ i ] ) - 1;
				sourceMin[ i ] = targetMin[ i ] * downsamplingFactors[ i ];
				sourceMax[ i ] = targetMax[ i ] * downsamplingFactors[ i ] + ( downsamplingFactors[ i ] - 1 );
			}

			sourceAndTargetIntervals.add( new Tuple2<>( new FinalInterval( sourceMin, sourceMax ), new FinalInterval( targetMin, targetMax ) ) );

			for ( d = 0; d < dim; ++d )
			{
				offset[ d ] += outputCellSize[ d ];
				if ( offset[ d ] < outputDimensions[ d ] )
					break;
				else
					offset[ d ] = 0;
			}
		}

		sparkContext.parallelize( sourceAndTargetIntervals ).foreach( sourceAndTargetInterval ->
		{
			final N5Writer n5Local = N5.openFSWriter( basePath );

			// TODO: can skip this target block if all source blocks are empty (not present)

			System.out.println( "Target interval: " + Arrays.toString( Intervals.minAsIntArray( sourceAndTargetInterval._2() ) ) + " to " + Arrays.toString( Intervals.maxAsIntArray( sourceAndTargetInterval._2() ) ) );

			final RandomAccessibleInterval< T > previousScaleLevelImg = N5Utils.open( n5Local, inputDatasetPath );
			final RandomAccessibleInterval< T > source = Views.offsetInterval( previousScaleLevelImg, sourceAndTargetInterval._1() );
			final Img< T > target = new ArrayImgFactory< T >().create( sourceAndTargetInterval._2(), Util.getTypeFromInterval( source ) );
			Downsample.downsample( source, target, downsamplingFactors );

			final long[] gridPosition = new long[ dim ];
			final CellGrid cellGrid = new CellGrid( outputDimensions, outputCellSize );
			cellGrid.getCellPosition( Intervals.minAsLongArray( sourceAndTargetInterval._2() ), gridPosition );

			N5Utils.saveBlock( target, n5Local, outputDatasetPath, gridPosition );
		} );
	}

	private static double getPixelResolutionZtoXY( final VoxelDimensions voxelDimensions )
	{
		return voxelDimensions.dimension( 2 ) / Math.max( voxelDimensions.dimension( 0 ), voxelDimensions.dimension( 1 ) );
	}

	private static Tuple2< Integer, Integer > getOptimalZCellSizeAndDownsamplingFactor( final int scaleLevel, final int cellSizeXY, final double pixelResolutionZToXY )
	{
		final int isotropicScaling = ( int ) Math.round( ( 1 << scaleLevel ) / pixelResolutionZToXY );
		final int zDownsamplingFactor = Math.max( isotropicScaling, 1 );
		final int fullScaleOptimalCellSize = ( int ) Math.round( cellSizeXY / pixelResolutionZToXY );
		final int zCellSize = ( fullScaleOptimalCellSize << scaleLevel ) / zDownsamplingFactor;
		return new Tuple2<>( zCellSize, zDownsamplingFactor );
	}
}