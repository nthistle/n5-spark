package org.janelia.saalfeldlab.n5.spark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.Bzip2Compression;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.N5FSReader;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import net.imglib2.Cursor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.basictypeaccess.array.ArrayDataAccess;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.IntType;
import net.imglib2.type.numeric.integer.ShortType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

public class N5ConvertSparkTest
{
	static private final String basePath = System.getProperty( "user.home" ) + "/tmp/n5-converter-test";
	static private final String datasetPath = "data";
	static private final String convertedDatasetPath = "converted-data";

	static private final N5WriterSupplier n5Supplier = () -> new N5FSWriter( basePath );

	private JavaSparkContext sparkContext;

	@Before
	public void setUp() throws IOException
	{
		// cleanup in case the test has failed
		tearDown();

		sparkContext = new JavaSparkContext( new SparkConf()
				.setMaster( "local[*]" )
				.setAppName( "N5ConverterTest" )
				.set( "spark.serializer", "org.apache.spark.serializer.KryoSerializer" )
			);
	}

	@After
	public void tearDown() throws IOException
	{
		if ( sparkContext != null )
			sparkContext.close();

		if ( Files.exists( Paths.get( basePath ) ) )
			cleanup( n5Supplier.get() );
	}

	private void cleanup( final N5Writer n5 ) throws IOException
	{
		Assert.assertTrue( n5.remove() );
	}

	@Test
	public void test() throws IOException
	{
		final N5Writer n5 = n5Supplier.get();
		final long[] dimensions = new long[] { 4, 5, 6 };
		N5Utils.save( createImage( new ShortType( ( short ) 20000 ), dimensions ), n5, datasetPath, new int[] { 2, 3, 1 }, new GzipCompression() );

		N5ConvertSpark.convert(
				sparkContext,
				() -> new N5FSReader( basePath ),
				datasetPath,
				n5Supplier,
				convertedDatasetPath,
				Optional.of( new int[] { 5, 1, 2 } ),
				Optional.of( new Bzip2Compression() ),
				Optional.of( DataType.UINT8 )
			);

		Assert.assertTrue( n5.datasetExists( convertedDatasetPath ) );

		final DatasetAttributes convertedAttributes = n5.getDatasetAttributes( convertedDatasetPath );
		Assert.assertArrayEquals( dimensions, convertedAttributes.getDimensions() );
		Assert.assertArrayEquals( new int[] { 5, 1, 2 }, convertedAttributes.getBlockSize() );
		Assert.assertEquals( new Bzip2Compression().getType(), convertedAttributes.getCompression().getType() );
		Assert.assertEquals( DataType.UINT8, convertedAttributes.getDataType() );

		Assert.assertArrayEquals(
				( int[] ) ( ( ArrayDataAccess< ? > ) createImage( new IntType( 205 ), dimensions ).update( null ) ).getCurrentStorageArray(),
				( int[] ) ( ( ArrayDataAccess< ? > ) getImgFromRandomAccessibleInterval( N5Utils.open( n5Supplier.get(), convertedDatasetPath ), new IntType() ).update( null ) ).getCurrentStorageArray()
			);
	}

	private < T extends NativeType< T > & RealType< T > > ArrayImg< T, ? > createImage( final T value, final long... dimensions )
	{
		final ArrayImg< T, ? > img = new ArrayImgFactory< T >().create( dimensions, value.createVariable() );
		final Cursor< T > imgCursor = img.cursor();
		while ( imgCursor.hasNext() )
			imgCursor.next().set( value );
		return img;
	}

	private < T extends NativeType< T > & RealType< T > > ArrayImg< T, ? > getImgFromRandomAccessibleInterval( final RandomAccessibleInterval< ? extends RealType< ? > > rai, final T value )
	{
		final ArrayImg< T, ? > img = new ArrayImgFactory< T >().create( Intervals.dimensionsAsLongArray( rai ), value.createVariable() );
		final Cursor< ? extends RealType< ? > > raiCursor = Views.flatIterable( rai ).cursor();
		final Cursor< T > imgCursor = Views.flatIterable( img ).cursor();
		while ( raiCursor.hasNext() || imgCursor.hasNext() )
			imgCursor.next().setReal( raiCursor.next().getRealDouble() );
		return img;
	}
}