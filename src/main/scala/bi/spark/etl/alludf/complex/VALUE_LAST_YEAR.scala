package bi.spark.etl.alludf.complex


import org.apache.spark.sql
import org.apache.spark.sql.SparkSession
import bi.spark.etl.alludf.datefun.dateReckon.dateReckonMonth
import bi.spark.etl.alludf.datefun.dayJudge._
import org.apache.spark.sql.functions._
import bi.spark.etl.inputpg.fileExists._
import bi.spark.etl.inputpg.readCsvUnion.readAndUnion


class VALUE_LAST_YEAR{

  def func(sparksql:SparkSession,df:sql.DataFrame,parms:Map[String,String]):sql.DataFrame={
    //注册两个函数
    val dayjudge = udf(dayJudgeDay)
    val datereckon = udf(dateReckonMonth)
//=================================================================================
    //获取field下的内容
    val field = parms("field").asInstanceOf[Map[String,String]]
    val re_pattern = """\w+""".r
    //VALUE_LAST_YEAR("DAY",[thedate], [Profit])
    //expr(0)是函数名字，expr(1)第一个参数：DAY/MONTH,expr(2)第二个参数日期列XXX__thedate,expr(3)第三个参数金额列
    val expr = (re_pattern findAllIn parms("addfield_expr")).toList
    val fixed_path = field(expr(3))
    //将传进来的表用一个临时表接收，之后的操作只对临时表做计算
    var df_tmp = df
    var set_path = scala.collection.mutable.Set[String]()
    //往前推算1年
    df_tmp = df_tmp.withColumn("LastYearMonthDay",datereckon(df_tmp(expr(2)),lit(-12)))
    //按天合计找值
    if(expr(1)=="""DAY"""){
//      df_tmp = df_tmp.withColumn("LastYearDay",add_months(df_tmp("AbsoluteDay"),-1))
      //计算推算后的日期是否与原来日期都存在，例如2020-02-29，往前推1年，只有2019-02-28，所以29返回null
      //不然倒推，会出现2词2019-02-28
      df_tmp = df_tmp.withColumn("LastYearMonthDay",dayjudge(df_tmp(expr(2)),df_tmp("LastYearMonthDay")))
//      df_tmp = df_tmp.withColumn("LastYearDay3",from_unixtime(df_tmp("LastYearDay").cast("float"),"yyyy-MM-dd"))
      //获取需要计算的日期，去重，后面用来拼接目录
//      df_tmp.select("LastYearDay").distinct().show()
      var datelist = df_tmp.select("LastYearMonthDay").distinct().collect()
      datelist.foreach( i =>{
        val year = i.getString(0).slice(0,4)
        val month = i.getString(0).slice(5,7)
        set_path += fixed_path + s"${year}/${month}"
      })
      //判断需要读入的目录是否存在，不存在就删去
      val path_list = fileExistsJudge(set_path).toList
      if(path_list.isEmpty){
        df_tmp = df_tmp.withColumn("Value_Last_Year",lit(0.0)).drop("LastYearMonthDay")//.drop("AbsoluteDay")
      }else{
        //递归读入数据
        var judge_table = readAndUnion(sparksql,path_list,true,"utf-8")
        //获取日期参数列名
        val the_date = expr(2).split("__").last
        //获取金额参数列名
        val target_value = expr(3).split("__").last
        judge_table = judge_table.select(s"${the_date}",s"${target_value}")
        //合计
        judge_table = judge_table.groupBy(s"${the_date}").sum(s"${target_value}")
        //拼接
        df_tmp = df_tmp.join(judge_table,df_tmp("LastYearMonthDay") === judge_table(s"${the_date}"),"left").drop(s"${the_date}").drop("LastYearMonthDay")
      }
      //按月合计找值
    }else if(expr(1)=="""MONTH"""){
      df_tmp = df_tmp.withColumn("LastYearMonth",substring(col("LastYearMonthDay"),0,7)).drop("LastYearMonthDay")
      //      df_tmp = df_tmp.withColumn("LastYearDay",add_months(df_tmp("AbsoluteDay"),-1))
      //计算推算后的日期是否与原来日期都存在，例如2020-02-29，往前推1年，只有2019-02-28，所以29返回null
      //不然倒推，会出现2词2019-02-28
//      df_tmp = df_tmp.withColumn("LastYearDay",dayjudge(df1(expr(2)),df_tmp("LastYearDay")))
      //      df_tmp = df_tmp.withColumn("LastYearDay3",from_unixtime(df_tmp("LastYearDay").cast("float"),"yyyy-MM-dd"))
      //获取需要计算的日期，去重，后面用来拼接目录
      var datelist = df_tmp.select("LastYearMonth").distinct().collect()
      datelist.foreach( i =>{
        val year = i.getString(0).slice(0,4)
        val month = i.getString(0).slice(5,7)
        set_path += fixed_path + s"${year}/${month}"
      })
      //判断需要读入的目录是否存在，不存在就删去
      val path_list = fileExistsJudge(set_path).toList
      if(path_list.isEmpty){
        df_tmp = df_tmp.withColumn("Value_Last_Year",lit(0.0)).drop("LastYearMonth")//.drop("AbsoluteDay")
      }else{
        //递归读入数据
        var judge_table = readAndUnion(sparksql,path_list,true,"utf-8")
        val the_date = expr(2).split("__").last
        val target_value = expr(3).split("__").last
        judge_table = judge_table.select(s"${the_date}",s"${target_value}")
//        judge_table = judge_table.withColumn("THEMonth",regexp_replace(col(s"${the_date}")," ",""))
        judge_table = judge_table.withColumn("THEMonth",substring(col(s"${the_date}"),0,7))
        judge_table = judge_table.groupBy("THEMonth").sum(s"${target_value}")
        df_tmp = df_tmp.join(judge_table,df_tmp("LastYearMonth") === judge_table("THEMonth"),"left").drop("THEMonth").drop("LastYearMonth")
      }
      //按年合计找值
    }else if(expr(1)=="""YEAR"""){
      df_tmp = df_tmp.withColumn("LastYearMonth",substring(col("LastYearMonthDay"),0,7)).drop("LastYearMonthDay")
     //获取需要计算的日期，去重，后面用来拼接目录
      var datelist = df_tmp.select("LastYearMonth").distinct().collect()
      datelist.foreach( i =>{
        val year = i.getString(0).slice(0,4)
        val month = i.getString(0).slice(5,7)
        set_path += fixed_path + s"${year}/${month}"
      })
      //判断需要读入的目录是否存在，不存在就删去
      val path_list = fileExistsJudge(set_path).toList
      if(path_list.isEmpty){
        df_tmp = df_tmp.withColumn("Value_Last_Year",lit(0.0)).drop("LastYearMonth")//.drop("AbsoluteDay")
      }else{
        //递归读入数据
        var judge_table = readAndUnion(sparksql,path_list,true,"utf-8")
        val the_date = expr(2).split("__").last
        val target_value = expr(3).split("__").last
        judge_table = judge_table.select(s"${the_date}",s"${target_value}")
        judge_table = judge_table.withColumn("THEYear",substring(col(s"${the_date}"),0,4))
        judge_table = judge_table.groupBy("THEYear").sum(s"${target_value}")
        df_tmp = df_tmp.withColumn("LastYear",substring(col("LastYearMonth"),0,4))
        df_tmp = df_tmp.join(judge_table,df_tmp("LastYear") === judge_table("THEYear"),"left").drop("THEMonth").drop("LastYearMonth").drop("LastYear")
      }
    }
    df_tmp
  }
}
