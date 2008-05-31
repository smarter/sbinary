package sbinary;

import java.io._
import scala.collection._;
import scala.collection.jcl.IdentityHashMap;
import immutable.TreeMap;
import mutable.ListBuffer;
import Instances._;
import sbinary.generic.Generic._;

class Input private[sbinary] (private[sbinary] val source : DataInput){
  def read[S](implicit bin : Binary[S]) : S = { forceLazyIO; bin.reads(this); }

  private[sbinary] def readByte : Byte = source.readByte;
  private[sbinary] def readUnsigned : Int = source.readByte & 255;

  private[sbinary] var streamInProgress = false;
  private[sbinary] var pendingStream : Stream[_] = null;
  private[sbinary] def forceLazyIO = 
    if (!streamInProgress && pendingStream != null) pendingStream.length
  
  private[sbinary] def readStream[S](implicit bin : Binary[S]) : Stream[S] = {
    if (readByte == 0) { Stream.empty }
    else {
      streamInProgress = true;
      try{
        val result = Stream.cons(bin.reads(this), readStream[S]);
        pendingStream = result;
        result;
      } finally { streamInProgress = false }
    }
  }

  /**
   * Returns an iterator that iterates by reading from this input.
   * In order to ensure proper laziness properties (and not reading more
   * data than is strictly neccessary) this will always return true 
   * from hasNext but may throw an EOFException on an unexpected end
   * of stream.
   */
  def asIterator[S](implicit bin : Binary[S]) = new Iterator[S]{
    def hasNext = true;
    def next = read[S];
  }
}
 
class Output private[sbinary] (private[sbinary] val source : DataOutput){
  def write[T](t : T)(implicit bin : Binary[T]) : Unit = bin.writes(t)(this); 
  private[sbinary] def writeByte(byte : Byte) = source.writeByte(byte); 
  def writeAll[T](ts : Iterable[T])(implicit bin : Binary[T]) : Unit = ts.foreach(write(_ : T));
}

/**
 * Trait for marshaling type T to and from binary data. 
 *
 * Because of the possibility of marshalling mutable types this library 
 * doesn't make any strong guarantees about equality. In general implementations
 * should make a best effort to ensure that read(write(t)) is in some sense equal
 * to t. This sense should be == if possible, but e.g. arrays are chosen so that
 * their contents are equal.  
 * 
 * Instances must be independent of platform and network byte order.  
 */
trait Binary[T]{
  /**
   * Read a T from the Input, reading no more data than is neccessary.
   */
  def reads(in : Input) : T;

  /**
   * Write a T to the Output. Return the number of bytes written.
   */
  def writes(t : T)(out : Output) : Unit; 
}

/**
 * Standard operations on binary types
 */
object Operations{
  implicit def wrapOutputStream(out : OutputStream) : Output = out match {
    case (x : DataOutputStream) => new Output(x);
    case y => new Output(new DataOutputStream(y));
  }
    
  implicit def wrapInputStream(in : InputStream) : Input = in match {
    case (x : DataInputStream) => new Input(x);
    case y => new Input(new DataInputStream(y));
  }
 
  implicit def wrapOutput(out : DataOutput) : Output = new Output(out);
  implicit def wrapInput(out : DataInput) : Input = new Input(out);

  implicit def fileByName(name : String) : File = new File(name);

  def binary[T](implicit bin : Binary[T]) = bin;

  /**
   * Get the serialized value of this class as a byte array.
   */
  def toByteArray[T](t : T)(implicit bin : Binary[T]) : Array[Byte] = {
    val target = new ByteArrayOutputStream();
    wrapOutputStream(target).write(t);
    target.toByteArray(); 
  }
 
  /**
   * Read a value from the byte array. Anything past the end of the value will be
   * ignored.
   */ 
  def fromByteArray[T](array : Array[Byte])(implicit bin : Binary[T]) = wrapInputStream(new ByteArrayInputStream(array)).read[T];

  /** 
   * Convenience method for writing binary data to a file.
   */
  def toFile[T](t : T)(file : File)(implicit bin : Binary[T]) = {
    val out = new BufferedOutputStream(new FileOutputStream(file));
    try{
      (out : Output).write(toByteArray(t));}
    finally{
      out.close(); }
  }

  /** 
   * Convenience method for reading binary data from a file.
   */
  def fromFile[T](file : File)(implicit bin : Binary[T]) = {
    val in = new BufferedInputStream(new FileInputStream(file))
    try{
      (in : Input).read[T]}
    finally{
      in.close(); }
  }
}

/**
 * Implicit instances for many standard types.
 */
object Instances{
  import Operations._;

  implicit object UnitIsBinary extends Binary[Unit]{
    def reads(in : Input) = ((), 0);
    def writes(t : Unit)(out : Output) = ();
  }

  implicit object StringIsBinary extends Binary[String]{
    def reads(in : Input) = in.source.readUTF();
    def writes(t : String)(out : Output) = out.source.writeUTF(t); 
  }

  implicit object BooleanIsBinary extends Binary[Boolean]{
    def reads(in : Input) = in.readByte != 0
    def writes(t : Boolean)(out : Output) = out.writeByte(if (t) (0x01) else (0x00));
  }

  implicit object ByteIsBinary extends Binary[Byte]{
    def reads(in : Input) = in.readByte
    def writes(t : Byte)(out : Output) = out.writeByte(t);
  }

  implicit object CharIsBinary extends Binary[Char]{
    def reads(in : Input) = ((in.readUnsigned << 8) + in.readUnsigned).toChar;
    def writes(t : Char)(out : Output) = {
      out.writeByte(((t >>> 8) & 0xFF).toByte);
      out.writeByte(((t >>> 0) & 0xFF).toByte);
    }
  }

  implicit object ShortIsBinary extends Binary[Short]{
    def reads(in : Input) = ((in.readUnsigned << 8) + in.readUnsigned).toShort

    def writes(t : Short)(out : Output) = {
      out.writeByte( ((t >>> 8) & 0xFF).toByte);
      out.writeByte( t.toByte);
    } 
  }

  implicit object IntIsBinary extends Binary[Int]{
    def reads(in : Input) = {
      val ch1 = in.readUnsigned;
      val ch2 = in.readUnsigned;
      val ch3 = in.readUnsigned;
      val ch4 = in.readUnsigned;
      ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4 << 0)) 
    }

    def writes(t : Int)(out : Output) = {
      out.writeByte(((t >>> 24) & 0xFF).toByte);
      out.writeByte(((t >>> 16) & 0xFF).toByte);
      out.writeByte(((t >>>  8) & 0xFF).toByte);
      out.writeByte(((t >>>  0) & 0xFF).toByte);
    } 
  }

  implicit object LongIsBinary extends Binary[Long]{
    def reads(in : Input) = ((in.readUnsigned.toLong << 56) +
                ((in.readUnsigned.toLong & 255).toLong << 48) +
            		((in.readUnsigned.toLong & 255) << 40) +
                ((in.readUnsigned.toLong & 255) << 32) +
                ((in.readUnsigned.toLong & 255) << 24) +
                ((in.readUnsigned & 255) << 16) +
                ((in.readUnsigned & 255) <<  8) +
                ((in.readUnsigned & 255) <<  0));
    def writes(t : Long)(out : Output) = {
      out.writeByte((t >>> 56).toByte);
      out.writeByte((t >>> 48).toByte);
      out.writeByte((t >>> 40).toByte);
      out.writeByte((t >>> 32).toByte);
      out.writeByte((t >>> 24).toByte);
      out.writeByte((t >>> 16).toByte);
      out.writeByte((t >>> 8).toByte);
      out.writeByte((t >>> 0).toByte);
    }
  }

  implicit object FloatIsBinary extends Binary[Float]{
    def reads(in : Input) = java.lang.Float.intBitsToFloat(in.read[Int])
    def writes(t : Float)(out : Output) = out.write[Int](java.lang.Float.floatToIntBits(t));
  }

  implicit object DoubleIsBinary extends Binary[Double]{
    def reads(in : Input) = java.lang.Double.longBitsToDouble(in.read[Long]);
    def writes(t : Double)(out : Output) = out.write[Long](java.lang.Double.doubleToLongBits(t));
  }

  implicit object BigIntIsBinary extends Binary[BigInt]{
    def reads(in : Input) = BigInt(in.read[Array[Byte]]);
    def writes(i : BigInt)(out : Output) = out.write(i.toByteArray);
  }

  implicit object BigDecimalIsBinary extends Binary[BigDecimal]{
    def reads(in : Input) = BigDecimal(in.read[String]);
    def writes(d : BigDecimal)(out : Output) = out.write(d.toString);
  }

  implicit object ClassIsBinary extends Binary[Class[T] forSome {type T;}]{
    def reads(in : Input) = Class.forName(in.read[String]);
    def writes(clazz : Class[T] forSome { type T; })(out : Output) = out.write(clazz.getName);
  }

  implicit val symbolIsBinary : Binary[Symbol] = viaString(Symbol(_));
  implicit val fileIsBinary : Binary[File] = viaString(new File(_ : String));

  import java.net.{URI, URL}
  implicit val urlIsBinary : Binary[URL] = viaString(new URL(_ : String));
  implicit val uriIsBinary : Binary[URI] = viaString(new URI(_ : String));


  import scala.xml.{XML, Elem, NodeSeq};
  implicit object xmlIsBinary extends Binary[NodeSeq]{
    def reads(in : Input) = XML.loadString(in.read[String]).child;
    def writes(elem : NodeSeq)(out : Output) = out.write(<binary>elem</binary>.toString);
  }

  implicit def listsAreBinary[T](implicit bin : Binary[T]) : Binary[List[T]] = 
    new LengthEncoded[List[T], T]{
      def build(length : Int, ts : Iterator[T]) = {
        val buffer = new ListBuffer[T];
        ts.foreach(buffer += (_ : T));
        buffer.toList;
      } 
    }

  implicit def arraysAreBinary[T](implicit bin : Binary[T]) : Binary[Array[T]] = 
    new LengthEncoded[Array[T], T]{
      def build(length : Int, ts : Iterator[T]) = {
        val result = new Array[T](length);
        ts.copyToArray(result, 0);
        result;
      }
    }

  implicit def immutableSetsAreBinary[T](implicit bin : Binary[T]) : Binary[immutable.Set[T]] = 
    viaArray((x : Array[T]) => immutable.Set(x :_*))

  implicit def immutableSortedSetsAreBinary[S](implicit ord : S => Ordered[S], binS : Binary[S]) : Binary[immutable.SortedSet[S]] = 
    viaArray( (x : Array[S]) => immutable.TreeSet[S](x :_*))

  implicit def immutableMapsAreBinary[S, T](implicit binS : Binary[S], binT : Binary[T]) : Binary[immutable.Map[S, T]] =
    viaArray( (x : Array[(S, T)]) => immutable.Map(x :_*));

  implicit def immutableSortedMapsAreBinary[S, T](implicit ord : S => Ordered[S], binS : Binary[S], binT : Binary[T]) : Binary[immutable.SortedMap[S, T]] =
    viaArray( (x : Array[(S, T)]) => TreeMap[S, T](x :_*))

  /**
   * Binary instance for streams.
   * Note that unlike almost all other collections this is not length encoded
   * Instead it is encoded with a sequence of byte separators, with a single
   * byte value of 1 preceding each element to be read and a value of 0 indicating
   * the stream termination.
   *
   * This is to ensure proper laziness behaviour - values will be written as they
   * become available rather than thunking the entire stream up front. 
   * 
   * Warning! The resulting Stream is read lazily. Be sure to force it before
   * reading anything else from the input stream. 
   */
  implicit def streamsAreBinary[S](implicit bin : Binary[S]) : Binary[Stream[S]] = new Binary[Stream[S]]{
    def reads(in : Input) = in.readStream[S] 

    def writes(stream : Stream[S])(out : Output){
      stream.foreach(x => { out.write[Byte](1); out.write(x); });
      out.write[Byte](0);
    }
  }

  implicit def optionsAreBinary[S](implicit bin : Binary[S]) : Binary[Option[S]] = new Binary[Option[S]]{
    def reads(in : Input) = in.read[Byte] match {
      case 1 => Some(in.read[S]);
      case 0 => None
    }

    def writes(s : Option[S])(out : Output) = s match {
      case Some(x) => { out.write[Byte](0x1); out.write(x) }
      case None => out.write[Byte](0x0);
    }
  }

<#list 2..22 as i>
  <#assign typeName>
   Tuple${i}[<#list 1..i as j>T${j} <#if i != j>,</#if></#list>]
  </#assign>
  implicit def tuple${i}sAreBinary[<#list 1..i as j>T${j}<#if i !=j>,</#if></#list>](implicit 
    <#list 1..i as j>
      bin${j} : Binary[T${j}] <#if i != j>,</#if>
    </#list>
    ) : Binary[${typeName}] = new Binary[${typeName}]{
      def reads (in : Input) : ${typeName} = ( 
    <#list 1..i as j>
        in.read[T${j}]<#if i!=j>,</#if>
    </#list>
      )
    
      def writes(tuple : ${typeName})(out : Output) = {
      <#list 1..i as j>
        out.write(tuple._${j});      
      </#list>;
      }
  }
</#list>

}
